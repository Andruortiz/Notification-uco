package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import org.junit.jupiter.api.Test;

class ProviderIdTest {

  @Test
  void ofPreservesGivenValue() {
    assertEquals("brevo", ProviderId.of("brevo").value());
  }

  @Test
  void rejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> ProviderId.of("   "));
  }

  @Test
  void rejectsNullValue() {
    assertThrows(IllegalArgumentException.class, () -> ProviderId.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(ProviderId.of("brevo"), ProviderId.of("brevo"));
  }
}
