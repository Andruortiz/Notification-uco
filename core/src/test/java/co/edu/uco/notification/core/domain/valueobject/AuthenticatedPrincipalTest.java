package co.edu.uco.notification.core.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AuthenticatedPrincipalTest {

  @Test
  void preservesGivenValues() {
    final AuthenticatedPrincipal principal =
        new AuthenticatedPrincipal("client-system-1", TenantId.of("tenant-a"), Role.CLIENTE);

    assertEquals("client-system-1", principal.subject());
    assertEquals(TenantId.of("tenant-a"), principal.tenantId());
    assertEquals(Role.CLIENTE, principal.role());
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
        () -> new AuthenticatedPrincipal("client-system-1", TenantId.of("tenant-a"), null));
  }
}
