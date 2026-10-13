package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import org.junit.jupiter.api.Test;

class TenantIdTest {

  @Test
  void ofPreservesGivenValue() {
    assertEquals("tenant-1", TenantId.of("tenant-1").value());
  }

  @Test
  void rejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> TenantId.of("   "));
  }

  @Test
  void rejectsNullValue() {
    assertThrows(IllegalArgumentException.class, () -> TenantId.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(TenantId.of("tenant-1"), TenantId.of("tenant-1"));
  }
}
