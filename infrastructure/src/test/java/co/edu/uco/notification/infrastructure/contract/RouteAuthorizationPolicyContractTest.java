package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.adapter.in.web.RouteAuthorizationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class RouteAuthorizationPolicyContractTest {

  private final RouteAuthorizationPolicy policy = new RouteAuthorizationPolicy();

  @Test
  void theInterimPolicyReturnsTheMinimumRoleDeclaredInTheContractForEveryOperation() {
    assertTrue(ApiContract.operations().size() >= 17);
    ApiContract.operations()
        .forEach(
            operation ->
                assertEquals(
                    Role.valueOf(operation.minimumRole()),
                    policy.minimumRoleFor(HttpMethod.valueOf(operation.method()), operation.path()),
                    operation.key()));
  }

  @Test
  void anUndeclaredRouteRequiresAdministrator() {
    assertEquals(Role.ADMINISTRADOR, policy.minimumRoleFor(HttpMethod.GET, "/undeclared"));
    assertEquals(Role.ADMINISTRADOR, policy.minimumRoleFor(HttpMethod.DELETE, "/notifications"));
  }

  @Test
  void aVersionOneRouteIsNoLongerRecognised() {
    assertEquals(
        Role.ADMINISTRADOR, policy.minimumRoleFor(HttpMethod.GET, "/notifications/abc-123"));
    assertEquals(
        Role.ADMINISTRADOR,
        policy.minimumRoleFor(HttpMethod.POST, "/attachment-uploads/u1:complete"));
  }
}
