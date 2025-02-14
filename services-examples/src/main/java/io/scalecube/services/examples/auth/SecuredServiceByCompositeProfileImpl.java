package io.scalecube.services.examples.auth;

import io.scalecube.services.auth.Authenticator;
import io.scalecube.services.exceptions.ForbiddenException;
import reactor.core.publisher.Mono;

public class SecuredServiceByCompositeProfileImpl implements SecuredServiceByCompositeProfile {

  @Override
  public Mono<String> hello(String name) {
    return Authenticator.deferSecured(CompositeProfile.class)
        .flatMap(
            compositeProfile -> {
              final UserProfile userProfile = compositeProfile.userProfile();
              final PrincipalProfile principalProfile = compositeProfile.principalProfile();
              checkPermissions(userProfile);
              return Mono.just(
                  "Hello, name="
                      + name
                      + " (userProfile="
                      + userProfile
                      + ", principalProfile="
                      + principalProfile
                      + ")");
            });
  }

  private void checkPermissions(UserProfile user) {
    if (!user.role().equals("ADMIN")) {
      throw new ForbiddenException("Forbidden");
    }
  }
}
