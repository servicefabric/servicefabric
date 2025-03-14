package io.scalecube.services.transport.rsocket;

import static io.scalecube.services.auth.Authenticator.AUTH_CONTEXT_KEY;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.rsocket.ConnectionSetupPayload;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.SocketAcceptor;
import io.rsocket.util.ByteBufPayload;
import io.scalecube.services.api.ServiceMessage;
import io.scalecube.services.auth.Authenticator;
import io.scalecube.services.exceptions.BadRequestException;
import io.scalecube.services.exceptions.MessageCodecException;
import io.scalecube.services.exceptions.ServiceException;
import io.scalecube.services.exceptions.ServiceUnavailableException;
import io.scalecube.services.exceptions.UnauthorizedException;
import io.scalecube.services.methods.ServiceMethodInvoker;
import io.scalecube.services.registry.api.ServiceRegistry;
import io.scalecube.services.transport.api.DataCodec;
import io.scalecube.services.transport.api.HeadersCodec;
import java.util.Collection;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public class RSocketServiceAcceptor implements SocketAcceptor {

  private static final Logger LOGGER = LoggerFactory.getLogger(RSocketServiceAcceptor.class);

  private final ConnectionSetupCodec connectionSetupCodec;
  private final HeadersCodec headersCodec;
  private final Collection<DataCodec> dataCodecs;
  private final Authenticator<Object> authenticator;
  private final ServiceRegistry serviceRegistry;

  /**
   * Constructor.
   *
   * @param connectionSetupCodec connectionSetupCodec
   * @param headersCodec headersCodec
   * @param dataCodecs dataCodecs
   * @param authenticator authenticator
   * @param serviceRegistry serviceRegistry
   */
  public RSocketServiceAcceptor(
      ConnectionSetupCodec connectionSetupCodec,
      HeadersCodec headersCodec,
      Collection<DataCodec> dataCodecs,
      Authenticator<Object> authenticator,
      ServiceRegistry serviceRegistry) {
    this.connectionSetupCodec = connectionSetupCodec;
    this.headersCodec = headersCodec;
    this.dataCodecs = dataCodecs;
    this.authenticator = authenticator;
    this.serviceRegistry = serviceRegistry;
  }

  @Override
  public Mono<RSocket> accept(ConnectionSetupPayload setupPayload, RSocket rsocket) {
    LOGGER.info("[rsocket][accept][{}] setup: {}", rsocket, setupPayload);

    return Mono.justOrEmpty(decodeConnectionSetup(setupPayload.data()))
        .flatMap(connectionSetup -> authenticate(rsocket, connectionSetup))
        .flatMap(authData -> Mono.fromCallable(() -> newRSocket(authData)))
        .switchIfEmpty(Mono.fromCallable(() -> newRSocket(null)))
        .cast(RSocket.class);
  }

  private ConnectionSetup decodeConnectionSetup(ByteBuf byteBuf) {
    // Work with byteBuf as usual and dont release it here, because it will be done by rsocket
    if (byteBuf.isReadable()) {
      try (ByteBufInputStream stream = new ByteBufInputStream(byteBuf, false /*releaseOnClose*/)) {
        return connectionSetupCodec.decode(stream);
      } catch (Throwable ex) {
        throw new MessageCodecException("Failed to decode connection setup", ex);
      }
    }
    return null;
  }

  private Mono<Object> authenticate(RSocket rsocket, ConnectionSetup connectionSetup) {
    if (authenticator == null || connectionSetup == null || !connectionSetup.hasCredentials()) {
      return Mono.empty();
    }
    return authenticator
        .apply(connectionSetup.credentials())
        .doOnSuccess(obj -> LOGGER.debug("[rsocket][authenticate][{}] authenticated", rsocket))
        .doOnError(
            ex ->
                LOGGER.error(
                    "[rsocket][authenticate][{}][error] cause: {}", rsocket, ex.toString()))
        .onErrorMap(RSocketServiceAcceptor::toUnauthorizedException);
  }

  private RSocket newRSocket(Object authData) {
    return new RSocketImpl(
        authData, new ServiceMessageCodec(headersCodec, dataCodecs), serviceRegistry);
  }

  private static UnauthorizedException toUnauthorizedException(Throwable th) {
    if (th instanceof ServiceException e) {
      return new UnauthorizedException(e.errorCode(), e.getMessage());
    } else {
      return new UnauthorizedException(th);
    }
  }

  @SuppressWarnings("ClassCanBeRecord")
  private static class RSocketImpl implements RSocket {

    private final Object authData;
    private final ServiceMessageCodec messageCodec;
    private final ServiceRegistry serviceRegistry;

    private RSocketImpl(
        Object authData, ServiceMessageCodec messageCodec, ServiceRegistry serviceRegistry) {
      this.authData = authData;
      this.messageCodec = messageCodec;
      this.serviceRegistry = serviceRegistry;
    }

    @Override
    public Mono<Payload> requestResponse(Payload payload) {
      return Mono.deferContextual(context -> Mono.just(toMessage(payload)))
          .doOnNext(RSocketImpl::validateRequest)
          .flatMap(
              message -> {
                final var methodInvoker = serviceRegistry.lookupInvoker(message);
                validateMethodInvoker(methodInvoker, message);
                return methodInvoker
                    .invokeOne(message)
                    .doOnNext(response -> releaseRequestOnError(message, response));
              })
          .map(this::toPayload)
          .doOnError(ex -> LOGGER.error("[requestResponse][error] cause: {}", ex.toString()))
          .contextWrite(this::setupContext);
    }

    @Override
    public Flux<Payload> requestStream(Payload payload) {
      return Mono.deferContextual(context -> Mono.just(toMessage(payload)))
          .doOnNext(RSocketImpl::validateRequest)
          .flatMapMany(
              message -> {
                final var methodInvoker = serviceRegistry.lookupInvoker(message);
                validateMethodInvoker(methodInvoker, message);
                return methodInvoker
                    .invokeMany(message)
                    .doOnNext(response -> releaseRequestOnError(message, response));
              })
          .map(this::toPayload)
          .doOnError(ex -> LOGGER.error("[requestStream][error] cause: {}", ex.toString()))
          .contextWrite(this::setupContext);
    }

    @Override
    public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
      return Flux.deferContextual(context -> Flux.from(payloads))
          .map(this::toMessage)
          .switchOnFirst(
              (first, messages) -> {
                if (first.hasValue()) {
                  final var message = first.get();
                  validateRequest(message);
                  final var methodInvoker = serviceRegistry.lookupInvoker(message);
                  validateMethodInvoker(methodInvoker, message);
                  return methodInvoker
                      .invokeBidirectional(messages)
                      .doOnNext(response -> releaseRequestOnError(message, response));
                }
                return messages;
              })
          .map(this::toPayload)
          .doOnError(ex -> LOGGER.error("[requestChannel][error] cause: {}", ex.toString()))
          .contextWrite(this::setupContext);
    }

    private Payload toPayload(ServiceMessage response) {
      return messageCodec.encodeAndTransform(response, ByteBufPayload::create);
    }

    private ServiceMessage toMessage(Payload payload) {
      try {
        return messageCodec.decode(payload.sliceData().retain(), payload.sliceMetadata().retain());
      } finally {
        payload.release();
      }
    }

    private Context setupContext(Context context) {
      return authData != null ? Context.of(AUTH_CONTEXT_KEY, authData) : context;
    }

    private static void validateRequest(ServiceMessage message) throws ServiceException {
      if (message == null) {
        throw new BadRequestException("Message is null, invocation failed");
      }
      if (message.qualifier() == null) {
        releaseRequest(message);
        throw new BadRequestException("Qualifier is null, invocation failed for " + message);
      }
    }

    private static void validateMethodInvoker(
        ServiceMethodInvoker methodInvoker, ServiceMessage message) {
      if (methodInvoker == null) {
        releaseRequest(message);
        LOGGER.error("No service invoker found, invocation failed for {}", message);
        throw new ServiceUnavailableException("No service invoker found");
      }
    }

    private static void releaseRequest(ServiceMessage request) {
      ReferenceCountUtil.safestRelease(request.data());
    }

    private static void releaseRequestOnError(ServiceMessage request, ServiceMessage response) {
      if (response.isError()) {
        releaseRequest(request);
      }
    }
  }
}
