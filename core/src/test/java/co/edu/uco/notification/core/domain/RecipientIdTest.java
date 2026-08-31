package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RecipientIdTest {

  @Test
  void ofPreservesGivenValue() {
    assertEquals("recipient-42", RecipientId.of("recipient-42").value());
  }

  @Test
  void rejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> RecipientId.of("   "));
  }

  @Test
  void rejectsNullValue() {
    assertThrows(IllegalArgumentException.class, () -> RecipientId.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(RecipientId.of("recipient-42"), RecipientId.of("recipient-42"));
  }
}
