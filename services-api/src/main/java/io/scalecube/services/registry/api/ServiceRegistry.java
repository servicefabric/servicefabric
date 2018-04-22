package io.scalecube.services.registry.api;

import io.scalecube.services.ServiceEndpoint;
import io.scalecube.services.ServiceReference;

import java.util.List;
import java.util.function.Predicate;

/**
 * Service registry interface provides API to register/unregister services in the system and make services lookup by
 * service result.
 */
public interface ServiceRegistry {

  ServiceEndpoint localEndpoint();

  List<ServiceEndpoint> listServiceEndpoints();

  List<ServiceReference> listServiceReferences();

  List<ServiceReference> lookupService(String namespace);

  List<ServiceReference> lookupService(Predicate<? super ServiceReference> filter);

  ServiceEndpoint registerService(ServiceEndpoint serviceEndpoint);

  ServiceEndpoint unregisterService(String endpointId);
}
