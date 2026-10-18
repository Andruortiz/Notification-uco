package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;

class RouteAuthorizationPolicyTest {

  private final RouteAuthorizationPolicy policy = new RouteAuthorizationPolicy();

  @Test
  void requiresNoElevatedRoleWhenThePepEnforces() {
    final var delegated =
        new RouteAuthorizationPolicy(
            new MockEnvironment().withProperty("security.pep.enforcement.enabled", "true"));

    assertEquals(Role.CLIENTE, delegated.minimumRoleFor(HttpMethod.GET, "/notifications"));
    assertEquals(Role.CLIENTE, delegated.minimumRoleFor(HttpMethod.GET, "/configuration"));
    assertEquals(Role.CLIENTE, delegated.minimumRoleFor(HttpMethod.GET, "/unknown"));
  }

  @Test
  void keepsLocalRulesWhenThePepIsNotEnforcing() {
    final var local = new RouteAuthorizationPolicy(new MockEnvironment());

    assertEquals(Role.OPERADOR, local.minimumRoleFor(HttpMethod.GET, "/notifications"));
    assertEquals(Role.ADMINISTRADOR, local.minimumRoleFor(HttpMethod.GET, "/unknown"));
  }

  @Test
  void keepsLocalRulesWhenSecurityIsDisabled() {
    final var disabled =
        new RouteAuthorizationPolicy(
            new MockEnvironment()
                .withProperty("security.enabled", "false")
                .withProperty("security.pep.enforcement.enabled", "true"));

    assertEquals(Role.ADMINISTRADOR, disabled.minimumRoleFor(HttpMethod.GET, "/configuration"));
  }

  @Test
  void searchRequiresOperador() {
    assertEquals(Role.OPERADOR, policy.minimumRoleFor(HttpMethod.GET, "/notifications"));
  }

  @Test
  void individualStatusRequiresOnlyCliente() {
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.GET, "/notifications/abc-123"));
  }

  @Test
  void sendRequiresOnlyCliente() {
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.POST, "/notifications"));
  }

  @Test
  void sendBatchRequiresOnlyCliente() {
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.POST, "/notifications:sendBatch"));
  }

  @Test
  void attachmentUploadFlowRequiresOnlyCliente() {
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.POST, "/attachment-uploads"));
    assertEquals(
        Role.CLIENTE, policy.minimumRoleFor(HttpMethod.POST, "/attachment-uploads/u1:complete"));
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.GET, "/attachment-uploads/u1"));
  }

  @Test
  void subscribeRequiresOnlyCliente() {
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.GET, "/notifications:subscribe"));
  }

  @Test
  void subscribeTicketRequiresOnlyCliente() {
    assertEquals(
        Role.CLIENTE, policy.minimumRoleFor(HttpMethod.POST, "/notifications:subscribeTicket"));
  }

  @Test
  void subscribeTicketIsNotOpenToOtherMethods() {
    assertEquals(
        Role.ADMINISTRADOR,
        policy.minimumRoleFor(HttpMethod.GET, "/notifications:subscribeTicket"));
  }

  @Test
  void catalogReadRequiresOnlyCliente() {
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.GET, "/channels"));
    assertEquals(Role.CLIENTE, policy.minimumRoleFor(HttpMethod.GET, "/providers"));
  }

  @Test
  void configurationReadRequiresAdministrador() {
    assertEquals(Role.ADMINISTRADOR, policy.minimumRoleFor(HttpMethod.GET, "/configuration"));
  }

  @Test
  void unknownRouteDefaultsToTheMostRestrictiveRoleNeverToOpenAccess() {
    assertEquals(
        Role.ADMINISTRADOR, policy.minimumRoleFor(HttpMethod.DELETE, "/notifications/abc-123"));
  }
}
