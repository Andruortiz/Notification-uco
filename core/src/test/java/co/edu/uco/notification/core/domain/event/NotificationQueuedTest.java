package co.edu.uco.notification.core.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.NotificationId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationQueuedTest {

  @Test
  void preservesGivenValues() {
    final NotificationId id = NotificationId.newId();
    final Instant now = Instant.now();

    final NotificationQueued event = new NotificationQueued(id, now);

    assertEquals(id, event.notificationId());
    assertEquals(now, event.occurredOn());
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(NullPointerException.class, () -> new NotificationQueued(null, Instant.now()));
  }

  @Test
  void rejectsNullOccurredOn() {
    assertThrows(
        NullPointerException.class, () -> new NotificationQueued(NotificationId.newId(), null));
  }
}
