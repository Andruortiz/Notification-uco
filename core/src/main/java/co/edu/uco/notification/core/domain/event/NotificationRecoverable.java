package co.edu.uco.notification.core.domain.event;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationRecoverable(
    NotificationId notificationId,
    TenantId tenantId,
    CorrelationId correlationId,
    Instant occurredOn)
    implements DomainEvent {

  public NotificationRecoverable {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(occurredOn, "occurredOn must not be null");
  }
}
