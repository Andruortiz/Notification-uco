package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.Recipient;
import org.junit.jupiter.api.Test;

class RecipientTest {

  @Test
  void ofPreservesGivenAddress() {
    assertEquals("alice@example.com", Recipient.of("alice@example.com").address());
  }

  @Test
  void rejectsBlankAddress() {
    assertThrows(IllegalArgumentException.class, () -> Recipient.of("   "));
  }

  @Test
  void rejectsNullAddress() {
    assertThrows(IllegalArgumentException.class, () -> Recipient.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(Recipient.of("alice@example.com"), Recipient.of("alice@example.com"));
  }
}
