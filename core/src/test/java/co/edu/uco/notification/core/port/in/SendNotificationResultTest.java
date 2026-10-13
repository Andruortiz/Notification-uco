package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import org.junit.jupiter.api.Test;

class SendNotificationResultTest {

  @Test
  void preservesGivenValues() {
    final NotificationId id = NotificationId.newId();

    final SendNotificationResult result =
        new SendNotificationResult(id, NotificationStatus.PENDING, false);

    assertEquals(id, result.notificationId());
    assertEquals(NotificationStatus.PENDING, result.status());
    assertFalse(result.duplicate());
  }

  @Test
  void preservesDuplicateFlagWhenTrue() {
    final SendNotificationResult result =
        new SendNotificationResult(NotificationId.newId(), NotificationStatus.DELIVERED, true);

    assertTrue(result.duplicate());
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationResult(null, NotificationStatus.PENDING, false));
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationResult(NotificationId.newId(), null, false));
  }
}
