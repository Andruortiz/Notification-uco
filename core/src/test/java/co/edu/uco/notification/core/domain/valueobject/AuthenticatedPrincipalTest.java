package co.edu.uco.notification.core.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthenticatedPrincipalTest {

  @Test
  void preservesGivenValues() {
    final AuthenticatedPrincipal principal =
        new AuthenticatedPrincipal("client-system-1", TenantId.of("tenant-a"), Role.CLIENTE);

    assertEquals("client-system-1", principal.subject());
    assertEquals(TenantId.of("tenant-a"), principal.tenantId());
    assertEquals(Set.of(Role.CLIENTE), principal.roles());
  }

  @Test
  void keepsTheWholeSetOfRoles() {
    final AuthenticatedPrincipal principal =
        new AuthenticatedPrincipal(
            "user-1", TenantId.of("tenant-a"), Set.of(Role.EDITOR_PLANTILLAS, Role.OPERADOR), null);

    assertEquals(Set.of(Role.EDITOR_PLANTILLAS, Role.OPERADOR), principal.roles());
  }

  @Test
  void onlyAdministratorsSeeUnmaskedData() {
    assertTrue(principalWith(Role.ADMINISTRADOR_PLATAFORMA).seesUnmaskedData());
    assertTrue(principalWith(Role.ADMINISTRADOR_APLICACION).seesUnmaskedData());
    assertFalse(principalWith(Role.EDITOR_PLANTILLAS).seesUnmaskedData());
    assertFalse(principalWith(Role.OPERADOR).seesUnmaskedData());
    assertFalse(principalWith(Role.AUDITOR).seesUnmaskedData());
    assertFalse(principalWith(Role.CLIENTE).seesUnmaskedData());
  }

  @Test
  void rejectsAnEmptySetOfRoles() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticatedPrincipal("user-1", TenantId.of("tenant-a"), Set.of(), null));
  }

  @Test
  void rejectsNullRoles() {
    assertThrows(
        NullPointerException.class,
        () -> new AuthenticatedPrincipal("user-1", TenantId.of("tenant-a"), null, null));
  }

  @Test
  void rejectsBlankSubject() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticatedPrincipal("  ", TenantId.of("tenant-a"), Role.CLIENTE));
  }

  @Test
  void rejectsNullSubject() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticatedPrincipal(null, TenantId.of("tenant-a"), Role.CLIENTE));
  }

  @Test
  void rejectsNullTenantId() {
    assertThrows(
        NullPointerException.class,
        () -> new AuthenticatedPrincipal("client-system-1", null, Role.CLIENTE));
  }

  @Test
  void rejectsNullRole() {
    assertThrows(
        NullPointerException.class,
        () -> new AuthenticatedPrincipal("client-system-1", TenantId.of("tenant-a"), (Role) null));
  }

  private static AuthenticatedPrincipal principalWith(final Role role) {
    return new AuthenticatedPrincipal("user-1", TenantId.of("tenant-a"), role);
  }
}
