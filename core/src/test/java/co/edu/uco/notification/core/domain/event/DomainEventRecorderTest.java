package co.edu.uco.notification.core.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainEventRecorderTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");

  @Test
  void pullEventsReturnsRecordedEventsInOrder() {
    final DomainEventRecorder recorder = new DomainEventRecorder();
    final NotificationId id = NotificationId.newId();
    final NotificationAccepted accepted = new NotificationAccepted(id, TENANT, null, Instant.now());
    final NotificationQueued queued = new NotificationQueued(id, TENANT, null, Instant.now());

    recorder.registerEvent(accepted);
    recorder.registerEvent(queued);

    final List<DomainEvent> pulled = recorder.pullEvents();

    assertEquals(List.of(accepted, queued), pulled);
  }

  @Test
  void pullEventsEmptiesTheRecorder() {
    final DomainEventRecorder recorder = new DomainEventRecorder();
    recorder.registerEvent(
        new NotificationAccepted(NotificationId.newId(), TENANT, null, Instant.now()));

    recorder.pullEvents();
    final List<DomainEvent> secondPull = recorder.pullEvents();

    assertTrue(secondPull.isEmpty());
  }

  @Test
  void startsEmpty() {
    assertTrue(new DomainEventRecorder().pullEvents().isEmpty());
  }
}
