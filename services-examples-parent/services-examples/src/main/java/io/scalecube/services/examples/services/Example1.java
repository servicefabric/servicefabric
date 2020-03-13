package io.scalecube.services.examples.services;

import io.scalecube.net.Address;
import io.scalecube.services.Scalecube;
import io.scalecube.services.discovery.ScalecubeServiceDiscovery;
import io.scalecube.services.transport.rsocket.RSocketServiceTransport;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class Example1 {

  /**
   * Main method.
   *
   * @param args - program arguments
   */
  public static void main(String[] args) {
    Scalecube gateway =
        Scalecube.builder()
            .discovery(ScalecubeServiceDiscovery::new)
            .transport(RSocketServiceTransport::new)
            .startAwait();

    final Address gatewayAddress = gateway.discovery().address();

    Scalecube service2Node =
        Scalecube.builder()
            .discovery(
                endpoint ->
                    new ScalecubeServiceDiscovery(endpoint)
                        .membership(cfg -> cfg.seedMembers(gatewayAddress)))
            .transport(RSocketServiceTransport::new)
            .services(new Service2Impl())
            .startAwait();

    Scalecube service1Node =
        Scalecube.builder()
            .discovery(
                endpoint ->
                    new ScalecubeServiceDiscovery(endpoint)
                        .membership(cfg -> cfg.seedMembers(gatewayAddress)))
            .transport(RSocketServiceTransport::new)
            .services(new Service1Impl())
            .startAwait();

    gateway
        .call()
        .api(Service1.class)
        .manyDelay(100)
        .publishOn(Schedulers.parallel())
        .take(10)
        .log("receive     |")
        .collectList()
        .log("complete    |")
        .block();

    Mono.whenDelayError(gateway.shutdown(), service1Node.shutdown(), service2Node.shutdown())
        .block();
  }
}
