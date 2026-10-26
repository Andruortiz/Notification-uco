package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.Role;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;

class RouteAuthorizationPolicyTest {

  private static final Set<Role> PLATFORM_ADMIN = Set.of(Role.ADMINISTRADOR_PLATAFORMA);

  private final RouteAuthorizationPolicy policy = new RouteAuthorizationPolicy();

  @Test
  void allowsEveryRoleWhenThePepEnforces() {
    final var delegated =
        new RouteAuthorizationPolicy(
            new MockEnvironment().withProperty("security.pep.enforcement.enabled", "true"));

    assertEquals(
        EnumSet.allOf(Role.class), delegated.allowedRolesFor(HttpMethod.GET, "/notifications"));
    assertEquals(
        EnumSet.allOf(Role.class), delegated.allowedRolesFor(HttpMethod.GET, "/configuration"));
    assertEquals(EnumSet.allOf(Role.class), delegated.allowedRolesFor(HttpMethod.GET, "/unknown"));
  }

  @Test
  void keepsLocalRulesWhenThePepIsNotEnforcing() {
    final var local = new RouteAuthorizationPolicy(new MockEnvironment());

    assertEquals(
        Set.of(Role.OPERADOR, Role.ADMINISTRADOR_APLICACION, Role.ADMINISTRADOR_PLATAFORMA),
        local.allowedRolesFor(HttpMethod.GET, "/notifications"));
    assertEquals(PLATFORM_ADMIN, local.allowedRolesFor(HttpMethod.GET, "/unknown"));
  }

  @Test
  void keepsLocalRulesWhenSecurityIsDisabled() {
    final var disabled =
        new RouteAuthorizationPolicy(
            new MockEnvironment()
                .withProperty("security.enabled", "false")
                .withProperty("security.pep.enforcement.enabled", "true"));

    assertEquals(PLATFORM_ADMIN, disabled.allowedRolesFor(HttpMethod.GET, "/unknown"));
  }

  @Test
  void searchIsOpenToOperadorAndBothAdministrators() {
    assertEquals(
        Set.of(Role.OPERADOR, Role.ADMINISTRADOR_APLICACION, Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.GET, "/notifications"));
  }

  @Test
  void postNotificationsIsNotOpenToAuditorOrOperador() {
    assertEquals(
        Set.of(Role.CLIENTE, Role.ADMINISTRADOR_APLICACION, Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.POST, "/notifications"));
  }

  @Test
  void templateWritesBelongToTheEditorAndThePlatformAdministrator() {
    assertEquals(
        Set.of(Role.EDITOR_PLANTILLAS, Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.POST, "/templates"));
    assertEquals(
        Set.of(Role.EDITOR_PLANTILLAS, Role.ADMINISTRADOR_PLATAFORMA),
        policy.allowedRolesFor(HttpMethod.PUT, "/templates"));
  }

  @Test
  void channelsAndProvidersAreManagedOnlyByThePlatformAdministrator() {
    assertEquals(PLATFORM_ADMIN, policy.allowedRolesFor(HttpMethod.POST, "/channels"));
    assertEquals(PLATFORM_ADMIN, policy.allowedRolesFor(HttpMethod.POST, "/providers"));
    assertEquals(PLATFORM_ADMIN, policy.allowedRolesFor(HttpMethod.POST, "/device-erasures"));
  }

  @Test
  void devicesAreOnlyOpenToTheClient() {
    assertEquals(Set.of(Role.CLIENTE), policy.allowedRolesFor(HttpMethod.POST, "/devices"));
    assertEquals(Set.of(Role.CLIENTE), policy.allowedRolesFor(HttpMethod.GET, "/devices"));
  }

  @Test
  void aVersionOneRouteAndAnUndeclaredMethodFallBackToThePlatformAdministrator() {
    assertEquals(PLATFORM_ADMIN, policy.allowedRolesFor(HttpMethod.GET, "/notifications/abc-123"));
    assertEquals(PLATFORM_ADMIN, policy.allowedRolesFor(HttpMethod.DELETE, "/notifications"));
  }
}
