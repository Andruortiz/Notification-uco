package co.edu.uco.notification.core.domain.event;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Instant;

public sealed interface DomainEvent
    permits NotificationAccepted,
        NotificationQueued,
        NotificationDelivered,
        NotificationFailed,
        NotificationRecoverable,
        NotificationRequeued,
        NotificationDiscarded {

  NotificationId notificationId();

  TenantId tenantId();

  CorrelationId correlationId();

  Instant occurredOn();
}
