package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.adapter.in.web.RouteAuthorizationPolicy;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class RouteAuthorizationPolicyContractTest {

  private final RouteAuthorizationPolicy policy = new RouteAuthorizationPolicy();

  @Test
  void theInterimPolicyReturnsTheAllowedRolesDeclaredInTheContractForEveryOperation() {
    assertEquals(52, ApiContract.operations().size());
    assertEquals(ApiContract.operations().size(), policy.declaredRouteCount());
    assertEquals(
        Set.of(Role.OPERADOR, Role.ADMINISTRADOR_APLICACION, Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.GET, "/provider-health"));
    ApiContract.operations()
        .forEach(
            operation ->
                assertEquals(
                    operation.allowedRoles().stream()
                        .map(Role::valueOf)
                        .collect(Collectors.toSet()),
                    policy.allowedRolesFor(
                        HttpMethod.valueOf(operation.method()), operation.path()),
                    operation.key()));
  }

  @Test
  void anUndeclaredRouteRequiresOnlyThePlatformAdministrator() {
    assertEquals(
        Set.of(Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.GET, "/undeclared"));
    assertEquals(
        Set.of(Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.DELETE, "/notifications"));
  }

  @Test
  void aVersionOneRouteIsNoLongerRecognised() {
    assertEquals(
        Set.of(Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.GET, "/notifications/abc-123"));
    assertEquals(
        Set.of(Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.POST, "/attachment-uploads/u1:complete"));
  }
}
