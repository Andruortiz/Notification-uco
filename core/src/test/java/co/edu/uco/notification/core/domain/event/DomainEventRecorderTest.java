package co.edu.uco.notification.core.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.NotificationId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainEventRecorderTest {

  @Test
  void pullEventsReturnsRecordedEventsInOrder() {
    final DomainEventRecorder recorder = new DomainEventRecorder();
    final NotificationId id = NotificationId.newId();
    final NotificationAccepted accepted = new NotificationAccepted(id, Instant.now());
    final NotificationQueued queued = new NotificationQueued(id, Instant.now());

    recorder.record(accepted);
    recorder.record(queued);

    final List<DomainEvent> pulled = recorder.pullEvents();

    assertEquals(List.of(accepted, queued), pulled);
  }

  @Test
  void pullEventsEmptiesTheRecorder() {
    final DomainEventRecorder recorder = new DomainEventRecorder();
    recorder.record(new NotificationAccepted(NotificationId.newId(), Instant.now()));

    recorder.pullEvents();
    final List<DomainEvent> secondPull = recorder.pullEvents();

    assertTrue(secondPull.isEmpty());
  }

  @Test
  void startsEmpty() {
    assertTrue(new DomainEventRecorder().pullEvents().isEmpty());
  }
}
