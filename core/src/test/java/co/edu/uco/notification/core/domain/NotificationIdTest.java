package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import org.junit.jupiter.api.Test;

class NotificationIdTest {

  @Test
  void newIdGeneratesNonBlankValue() {
    final NotificationId id = NotificationId.newId();
    assertFalse(id.value().isBlank());
  }

  @Test
  void newIdGeneratesUniqueValues() {
    assertNotEquals(NotificationId.newId(), NotificationId.newId());
  }

  @Test
  void ofPreservesGivenValue() {
    assertEquals("abc-123", NotificationId.of("abc-123").value());
  }

  @Test
  void rejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> NotificationId.of("   "));
  }

  @Test
  void rejectsNullValue() {
    assertThrows(IllegalArgumentException.class, () -> NotificationId.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(NotificationId.of("abc-123"), NotificationId.of("abc-123"));
  }
}
