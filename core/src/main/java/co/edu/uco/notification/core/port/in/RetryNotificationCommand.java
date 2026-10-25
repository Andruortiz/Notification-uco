package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;

public record RetryNotificationCommand(TenantId tenantId, NotificationId notificationId) {

  public RetryNotificationCommand {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
  }
}
