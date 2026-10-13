package co.edu.uco.notification.core.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationDiscardedTest {

  private static final TenantId TENANT = TenantId.of("tenant-a");

  @Test
  void preservesGivenValues() {
    final NotificationId id = NotificationId.newId();
    final CorrelationId correlationId = CorrelationId.of("corr-1");
    final Instant now = Instant.now();

    final NotificationDiscarded event = new NotificationDiscarded(id, TENANT, correlationId, now);

    assertEquals(id, event.notificationId());
    assertEquals(TENANT, event.tenantId());
    assertEquals(correlationId, event.correlationId());
    assertEquals(now, event.occurredOn());
  }

  @Test
  void allowsMissingCorrelationIdForLegacyNotifications() {
    final NotificationDiscarded event =
        new NotificationDiscarded(NotificationId.newId(), TENANT, null, Instant.now());

    assertNull(event.correlationId());
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(
        NullPointerException.class,
        () -> new NotificationDiscarded(null, TENANT, null, Instant.now()));
  }

  @Test
  void rejectsNullTenantId() {
    assertThrows(
        NullPointerException.class,
        () -> new NotificationDiscarded(NotificationId.newId(), null, null, Instant.now()));
  }

  @Test
  void rejectsNullOccurredOn() {
    assertThrows(
        NullPointerException.class,
        () -> new NotificationDiscarded(NotificationId.newId(), TENANT, null, null));
  }
}
