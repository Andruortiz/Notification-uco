package co.edu.uco.notification.core.domain.event;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import java.time.Instant;

public sealed interface DomainEvent
    permits NotificationAccepted, NotificationQueued, NotificationDelivered, NotificationFailed {

  NotificationId notificationId();

  Instant occurredOn();
}
