//package io.scalecube.services.gateway.http;
//
//import io.scalecube.services.ServiceInfo;
//import io.scalecube.services.gateway.AbstractLocalGatewayExtension;
//import io.scalecube.services.gateway.GatewayOptions;
//import io.scalecube.services.gateway.client.GatewayClientTransports;
//import java.util.function.Function;
//
//class HttpLocalGatewayExtension extends AbstractLocalGatewayExtension {
//
//  private static final String GATEWAY_ALIAS_NAME = "http";
//
//  HttpLocalGatewayExtension(Object serviceInstance) {
//    this(ServiceInfo.fromServiceInstance(serviceInstance).build());
//  }
//
//  HttpLocalGatewayExtension(ServiceInfo serviceInfo) {
//    this(serviceInfo, opts -> new HttpGateway.Builder().options(opts).build());
//  }
//
//  HttpLocalGatewayExtension(
//      ServiceInfo serviceInfo, Function<GatewayOptions, HttpGateway> gatewaySupplier) {
//    super(
//        serviceInfo,
//        opts -> gatewaySupplier.apply(opts.id(GATEWAY_ALIAS_NAME)),
//        GatewayClientTransports::httpGatewayClientTransport);
//  }
//}
