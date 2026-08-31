package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NotificationContentTest {

  @Test
  void ofWithSubjectAndBodyPreservesBoth() {
    final NotificationContent content = NotificationContent.of("Subject", "Body");
    assertEquals("Subject", content.subject());
    assertEquals("Body", content.body());
  }

  @Test
  void ofWithOnlyBodyLeavesSubjectNull() {
    final NotificationContent content = NotificationContent.of("Body");
    assertNull(content.subject());
    assertEquals("Body", content.body());
  }

  @Test
  void rejectsBlankBody() {
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of("Subject", "   "));
  }

  @Test
  void rejectsNullBody() {
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of(null));
  }

  @Test
  void acceptsBodyAtMaxLength() {
    final String body = "a".repeat(NotificationContent.MAX_LENGTH);
    assertEquals(NotificationContent.MAX_LENGTH, NotificationContent.of(body).body().length());
  }

  @Test
  void rejectsBodyOverMaxLength() {
    final String body = "a".repeat(NotificationContent.MAX_LENGTH + 1);
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of(body));
  }

  @Test
  void rejectsSubjectPlusBodyOverMaxLength() {
    final String subject = "a".repeat(NotificationContent.MAX_LENGTH);
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of(subject, "b"));
  }
}
