package co.edu.uco.notification.core.domain.event;

import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationDelivered(NotificationId notificationId, Instant occurredOn)
    implements DomainEvent {

  public NotificationDelivered {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(occurredOn, "occurredOn must not be null");
  }
}
