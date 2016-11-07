package io.scalecube.services.annotations;

import io.scalecube.services.ServiceDefinition;

import java.util.Collection;
import java.util.Map;


public interface ServiceProcessor {

  Collection<Class<?>> extractServiceInterfaces(Object serviceObject);

  Map<String, ServiceDefinition> introspectServiceInterface(Class<?> serviceInterface);

}
