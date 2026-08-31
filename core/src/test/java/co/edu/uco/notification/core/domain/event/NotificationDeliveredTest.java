package co.edu.uco.notification.core.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.NotificationId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationDeliveredTest {

  @Test
  void preservesGivenValues() {
    final NotificationId id = NotificationId.newId();
    final Instant now = Instant.now();

    final NotificationDelivered event = new NotificationDelivered(id, now);

    assertEquals(id, event.notificationId());
    assertEquals(now, event.occurredOn());
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(NullPointerException.class, () -> new NotificationDelivered(null, Instant.now()));
  }

  @Test
  void rejectsNullOccurredOn() {
    assertThrows(
        NullPointerException.class, () -> new NotificationDelivered(NotificationId.newId(), null));
  }
}
