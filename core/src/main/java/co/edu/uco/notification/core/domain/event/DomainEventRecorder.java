package co.edu.uco.notification.core.domain.event;

import java.util.ArrayList;
import java.util.List;

public final class DomainEventRecorder {

  private final List<DomainEvent> events = new ArrayList<>();

  public void registerEvent(final DomainEvent event) {
    events.add(event);
  }

  public List<DomainEvent> pullEvents() {
    final List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }
}
