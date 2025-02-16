package io.scalecube.services.sut.security;

import io.scalecube.services.annotations.Service;
import io.scalecube.services.annotations.ServiceMethod;
import io.scalecube.services.auth.Secured;
import reactor.core.publisher.Mono;

@Secured
@Service("secured")
public interface SecuredService {

  @ServiceMethod
  Mono<String> helloWithRequest(String name);

  @ServiceMethod
  Mono<String> helloWithPrincipal();

  @ServiceMethod
  Mono<String> helloWithRequestAndPrincipal(String name);

  @Secured(roles = {"helloRole1", "helloRole2"})
  @ServiceMethod
  Mono<String> helloWithRoles(String name);

  @Secured(roles = "helloPermission", permissions = "hello:permissions")
  @ServiceMethod
  Mono<String> helloWithPermissions(String name);
}
