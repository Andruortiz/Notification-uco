package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

class AuthenticatedPrincipalArgumentResolverTest {

  private final AuthenticatedPrincipalArgumentResolver resolver =
      new AuthenticatedPrincipalArgumentResolver();

  @Test
  void supportsAuthenticatedPrincipalParameter() {
    assertTrue(resolver.supportsParameter(principalParameter()));
  }

  @Test
  void doesNotSupportOtherParameterTypes() {
    assertFalse(resolver.supportsParameter(stringParameter()));
  }

  @Test
  void resolvesPrincipalFromExchangeAttribute() {
    final AuthenticatedPrincipal principal =
        new AuthenticatedPrincipal("client-1", TenantId.of("tenant-a"), Role.CLIENTE);
    final ServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/notifications").build());
    exchange.getAttributes().put(AuthenticationWebFilter.PRINCIPAL_ATTRIBUTE, principal);

    StepVerifier.create(
            resolver.resolveArgument(principalParameter(), new BindingContext(), exchange))
        .expectNext(principal)
        .verifyComplete();
  }

  private static MethodParameter principalParameter() {
    return new MethodParameter(dummyMethod(), 0);
  }

  private static MethodParameter stringParameter() {
    return new MethodParameter(dummyMethod(), 1);
  }

  private static Method dummyMethod() {
    try {
      return DummyController.class.getDeclaredMethod(
          "handle", AuthenticatedPrincipal.class, String.class);
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class DummyController {
    void handle(final AuthenticatedPrincipal principal, final String other) {}
  }
}
