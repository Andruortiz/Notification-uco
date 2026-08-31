package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.NotificationStatus;
import org.junit.jupiter.api.Test;

class SendNotificationResultTest {

  @Test
  void preservesGivenValues() {
    final NotificationId id = NotificationId.newId();

    final SendNotificationResult result =
        new SendNotificationResult(id, NotificationStatus.PENDING);

    assertEquals(id, result.notificationId());
    assertEquals(NotificationStatus.PENDING, result.status());
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationResult(null, NotificationStatus.PENDING));
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(
        NullPointerException.class, () -> new SendNotificationResult(NotificationId.newId(), null));
  }
}
