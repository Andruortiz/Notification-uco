package co.edu.uco.notification.core.domain.event;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationAccepted(NotificationId notificationId, Instant occurredOn)
    implements DomainEvent {

  public NotificationAccepted {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(occurredOn, "occurredOn must not be null");
  }
}
