package co.edu.uco.notification.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import org.junit.jupiter.api.Test;

class NotificationNotFoundExceptionTest {

  @Test
  void messageIncludesNotificationId() {
    final NotificationId id = NotificationId.of("notif-1");

    final NotificationNotFoundException exception = new NotificationNotFoundException(id);

    assertEquals("Notification not found: notif-1", exception.getMessage());
  }
}
