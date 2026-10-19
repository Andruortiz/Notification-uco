package co.edu.uco.notification.core.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SessionCredentialTest {

  @Test
  void keepsTheValue() {
    assertEquals("abc123", new SessionCredential("abc123").value());
  }

  @Test
  void rejectsNullBlankAndEmptyValues() {
    assertThrows(IllegalArgumentException.class, () -> new SessionCredential(null));
    assertThrows(IllegalArgumentException.class, () -> new SessionCredential(""));
    assertThrows(IllegalArgumentException.class, () -> new SessionCredential("   "));
  }

  @Test
  void toStringDoesNotRevealTheValue() {
    assertFalse(new SessionCredential("super-secret-cookie").toString().contains("super-secret"));
  }
}
