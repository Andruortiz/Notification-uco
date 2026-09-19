package co.edu.uco.notification.core.domain.event;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationDiscarded(NotificationId notificationId, Instant occurredOn)
    implements DomainEvent {

  public NotificationDiscarded {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(occurredOn, "occurredOn must not be null");
  }
}
