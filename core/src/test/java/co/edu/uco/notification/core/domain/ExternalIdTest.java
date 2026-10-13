package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import org.junit.jupiter.api.Test;

class ExternalIdTest {

  @Test
  void ofPreservesGivenValue() {
    assertEquals("order-42", ExternalId.of("order-42").value());
  }

  @Test
  void rejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> ExternalId.of("   "));
  }

  @Test
  void rejectsNullValue() {
    assertThrows(IllegalArgumentException.class, () -> ExternalId.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(ExternalId.of("order-42"), ExternalId.of("order-42"));
  }
}
