package io.scalecube.services;

import io.scalecube.services.auth.Authenticator;
import io.scalecube.services.exceptions.ServiceProviderErrorMapper;
import io.scalecube.services.transport.api.ServiceMessageDataDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceInfo {

  private final Object serviceInstance;
  private final Map<String, String> tags;
  private final ServiceProviderErrorMapper errorMapper;
  private final ServiceMessageDataDecoder dataDecoder;
  private final Authenticator authenticator;
  private final Logger logger;

  private ServiceInfo(Builder builder) {
    this.serviceInstance = builder.serviceInstance;
    this.tags = Collections.unmodifiableMap(new HashMap<>(builder.tags));
    this.errorMapper = builder.errorMapper;
    this.dataDecoder = builder.dataDecoder;
    this.authenticator = builder.authenticator;
    this.logger = builder.logger;
  }

  public static Builder from(ServiceInfo serviceInfo) {
    return new Builder(serviceInfo);
  }

  public static Builder fromServiceInstance(Object serviceInstance) {
    return new Builder(serviceInstance);
  }

  public Object serviceInstance() {
    return serviceInstance;
  }

  public Map<String, String> tags() {
    return tags;
  }

  public ServiceProviderErrorMapper errorMapper() {
    return errorMapper;
  }

  public ServiceMessageDataDecoder dataDecoder() {
    return dataDecoder;
  }

  public Authenticator authenticator() {
    return authenticator;
  }

  public Logger logger() {
    return logger;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", ServiceInfo.class.getSimpleName() + "[", "]")
        .add("serviceInstance=" + serviceInstance)
        .add("tags=" + tags)
        .add("errorMapper=" + errorMapper)
        .add("dataDecoder=" + dataDecoder)
        .add("authenticator=" + authenticator)
        .add("logger=" + logger)
        .toString();
  }

  public static class Builder {

    private final Object serviceInstance;
    private final Map<String, String> tags = new HashMap<>();
    private ServiceProviderErrorMapper errorMapper;
    private ServiceMessageDataDecoder dataDecoder;
    private Authenticator authenticator;
    private Logger logger;

    private Builder(ServiceInfo serviceInfo) {
      this.serviceInstance = serviceInfo.serviceInstance;
      this.tags.putAll(new HashMap<>(serviceInfo.tags));
      this.errorMapper = serviceInfo.errorMapper;
      this.dataDecoder = serviceInfo.dataDecoder;
      this.authenticator = serviceInfo.authenticator;
      this.logger = serviceInfo.logger;
    }

    private Builder(Object serviceInstance) {
      this.serviceInstance = serviceInstance;
    }

    /**
     * Setter for {@code tags}. Merges this {@code tags} with {@code Microservices.tags}. If keys
     * are clashing this {@code tags} shall override {@code Microservices.tags}.
     *
     * @param key tag key
     * @param value tag value
     * @return this
     */
    public Builder tag(String key, String value) {
      Objects.requireNonNull(key, "tag key");
      Objects.requireNonNull(value, "tag value");
      tags.put(key, value);
      return this;
    }

    /**
     * Setter for {@code errorMapper}. Overrides default {@code Microservices.errorMapper}.
     *
     * @param errorMapper error mapper
     * @return this
     */
    public Builder errorMapper(ServiceProviderErrorMapper errorMapper) {
      this.errorMapper = Objects.requireNonNull(errorMapper, "errorMapper");
      return this;
    }

    /**
     * Setter for {@code logger}. Overrides default {@code Microservices.logger}.
     *
     * @param name logger name (optional)
     * @return this
     */
    public Builder logger(String name) {
      this.logger = name != null ? LoggerFactory.getLogger(name) : null;
      return this;
    }

    /**
     * Setter for {@code logger}. Overrides default {@code Microservices.logger}.
     *
     * @param clazz logger name (optional)
     * @return this
     */
    public Builder logger(Class<?> clazz) {
      this.logger = clazz != null ? LoggerFactory.getLogger(clazz) : null;
      return this;
    }

    /**
     * Setter for {@code logger}. Overrides default {@code Microservices.logger}.
     *
     * @param logger logger (optional)
     * @return this
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Setter for {@code dataDecoder}. Overrides default {@code Microservices.dataDecoder}.
     *
     * @param dataDecoder data decoder
     * @return this
     */
    public Builder dataDecoder(ServiceMessageDataDecoder dataDecoder) {
      this.dataDecoder = Objects.requireNonNull(dataDecoder, "dataDecoder");
      return this;
    }

    /**
     * Setter for {@code authenticator}. Overrides default {@code Microservices.authenticator}.
     *
     * @param authenticator authenticator (optional)
     * @return this
     */
    public Builder authenticator(Authenticator authenticator) {
      this.authenticator = authenticator;
      return this;
    }

    Builder errorMapperIfAbsent(ServiceProviderErrorMapper errorMapper) {
      if (this.errorMapper == null) {
        return errorMapper(errorMapper);
      }
      return this;
    }

    Builder dataDecoderIfAbsent(ServiceMessageDataDecoder dataDecoder) {
      if (this.dataDecoder == null) {
        return dataDecoder(dataDecoder);
      }
      return this;
    }

    Builder authenticatorIfAbsent(Authenticator authenticator) {
      if (this.authenticator == null) {
        return authenticator(authenticator);
      }
      return this;
    }

    Builder loggerIfAbsent(Logger logger) {
      if (this.logger == null) {
        return logger(logger);
      }
      return this;
    }

    public ServiceInfo build() {
      return new ServiceInfo(this);
    }
  }
}
