package org.consul.registry.integration;

import java.util.List;
import java.util.stream.Collectors;

import com.orbitz.consul.AgentClient;
import com.orbitz.consul.Consul;
import com.orbitz.consul.model.agent.ImmutableRegistration;
import com.orbitz.consul.model.agent.Registration;
import com.orbitz.consul.model.health.Service;

import io.scalecube.cluster.ICluster;
import io.scalecube.services.RemoteServiceInstance;
import io.scalecube.services.ServiceDiscovery;
import io.scalecube.services.ServiceReference;
import io.scalecube.services.ServiceRegistration;
import io.scalecube.transport.Address;

public class ConsulServiceDiscovery implements ServiceDiscovery {
  
  private static final String CONSUL = "consul";

  private static final String MICROSERVICE = "microservice";

  final Consul consul;

  final AgentClient agentClient;

  private ICluster cluster;

  public ConsulServiceDiscovery() {
    consul = Consul.builder()
        // .withHostAndPort(HostAndPort.fromString(AppConfig.consulAddress()))
        .build();
    agentClient = consul.agentClient();
  }

  public void registerService(ServiceRegistration registration) {
    Registration reg = ImmutableRegistration.builder()
        .address(registration.ip())
        .port(registration.port())
        .name(registration.serviceName())
        .id(registration.memberId())
        .addTags(registration.tags())
        .build();

    // register new service
    agentClient.register(reg);
  }

  public List<RemoteServiceInstance> getRemoteServices() {
    return consul.agentClient().getServices().values().stream()
        .filter(entry -> isValidService(entry))
        .map(service -> toServiceInstance(
            service.getId(),
            service.getService(),
            service.getAddress(),
            service.getPort(),
            service.getTags().toArray(new String[service.getTags().size()])
            ))
        .collect(Collectors.toList());
  }

  public List<RemoteServiceInstance> serviceLookup(final String serviceName) {
    return getRemoteServices().stream().filter(
        service -> {
          return service.equals(serviceName);
        }).collect(Collectors.toList());
  }

  @Override
  public void cluster(ICluster cluster) {
    this.cluster = cluster;
  }

  private boolean isValidService(Service service) {
    if (service.getService().equals(CONSUL)) {
      return false;
    }
    if (!service.getId().equals(cluster.member().id()) && service.getTags().contains(MICROSERVICE)) {
      return true;
    } else {
      return false;
    }
  }

  private RemoteServiceInstance toServiceInstance(String id, String service, String host, int port,String[] tags) {
    return new RemoteServiceInstance(cluster, new ServiceReference(id, service,Address.create(host, port), tags));
  }

  @Override
  public void cleanup() {
    consul.agentClient().getServices().values().stream()
        .filter(
            service -> {
              return (!cluster.member().id().equals(service.getId()) && // don't remove my registrations
                  !service.getService().equals(CONSUL) &&          // and don't remove consul service
                   service.getTags().contains(MICROSERVICE));
            })
        .forEach(service -> {
          if (!cluster.member(service.getId()).isPresent()) {
            consul.agentClient().deregister(service.getId());
          }
        });
  }


}
