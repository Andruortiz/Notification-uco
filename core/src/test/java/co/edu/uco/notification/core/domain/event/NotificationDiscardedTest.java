package co.edu.uco.notification.core.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationDiscardedTest {

  @Test
  void preservesGivenValues() {
    final NotificationId id = NotificationId.newId();
    final Instant now = Instant.now();

    final NotificationDiscarded event = new NotificationDiscarded(id, now);

    assertEquals(id, event.notificationId());
    assertEquals(now, event.occurredOn());
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(NullPointerException.class, () -> new NotificationDiscarded(null, Instant.now()));
  }

  @Test
  void rejectsNullOccurredOn() {
    assertThrows(
        NullPointerException.class, () -> new NotificationDiscarded(NotificationId.newId(), null));
  }
}
