package io.scalecube.transport;

import static io.scalecube.transport.TransportHandshakeData.Q_TRANSPORT_HANDSHAKE_SYNC;
import static io.scalecube.transport.TransportHandshakeData.Q_TRANSPORT_HANDSHAKE_SYNC_ACK;
import static io.scalecube.transport.TransportHeaders.QUALIFIER;

import io.scalecube.transport.utils.FutureUtils;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbound handler. Recognizes only handshake message ({@link TransportHandshakeData#Q_TRANSPORT_HANDSHAKE_SYNC} (rest
 * messages unsupported and results in {@link TransportBrokenException}).
 */
@ChannelHandler.Sharable
final class AcceptorHandshakeChannelHandler extends ChannelInboundHandlerAdapter {
  static final Logger LOGGER = LoggerFactory.getLogger(AcceptorHandshakeChannelHandler.class);

  final ITransportSpi transportSpi;

  public AcceptorHandshakeChannelHandler(ITransportSpi transportSpi) {
    this.transportSpi = transportSpi;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    Message message = (Message) msg;
    if (!Q_TRANSPORT_HANDSHAKE_SYNC.equals(message.header(QUALIFIER))) {
      throw new TransportBrokenException("Received unsupported " + msg
          + " (though expecting only Q_TRANSPORT_HANDSHAKE_SYNC)");
    }

    final TransportChannel transportChannel = TransportChannel.from(ctx.channel());
    final TransportHandshakeData handshakeRequest = message.data();
    final TransportHandshakeData handshakeResponse =
        prepareHandshakeResponse(handshakeRequest, transportSpi.localEndpoint());

    if (handshakeResponse.isResolvedOk()) {
      transportChannel.setHandshakeData(handshakeRequest);
      transportSpi.accept(transportChannel);
      transportSpi.resetDueHandshake(transportChannel.channel());
      transportChannel.flip(TransportChannel.Status.CONNECTED, TransportChannel.Status.READY);
      LOGGER.debug("Set READY on acceptor: {}", transportChannel);
    }

    Message handshakeAckMsg = Message.withData(handshakeResponse).qualifier(Q_TRANSPORT_HANDSHAKE_SYNC_ACK).build();
    ChannelFuture channelFuture = ctx.writeAndFlush(handshakeAckMsg);

    channelFuture.addListener(FutureUtils.wrap(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) {
        if (!handshakeResponse.isResolvedOk()) {
          LOGGER.debug("HANDSHAKE({}) not passed, acceptor: {}", handshakeResponse, transportChannel);
          transportChannel.close(new TransportHandshakeException(handshakeResponse.explain()));
        }
      }
    }));
  }

  /**
   * Handshake factory method on <i>acceptor</i> side. Performs basic validation by comparing remote endpoint id vs
   * local endpoint id, they must not be equal.
   *
   * @param handshakeRequest incoming (remote) handshake from <i>connector</i>
   * @param localEndpoint local endpoint
   * @return {@link TransportHandshakeData} object in status RESOLVED_OK or RESOLVED_ERR
   */
  private TransportHandshakeData prepareHandshakeResponse(TransportHandshakeData handshakeRequest,
      TransportEndpoint localEndpoint) {
    TransportEndpoint remoteEndpoint = handshakeRequest.endpoint();
    String remoteEndpointId = remoteEndpoint.id();
    if (remoteEndpointId.equals(localEndpoint.id())) {
      return TransportHandshakeData.error(localEndpoint,
          String.format("Remote endpoint id: %s is eq to local one: %s", remoteEndpoint, remoteEndpointId));
    }
    return TransportHandshakeData.ok(localEndpoint);
  }
}
