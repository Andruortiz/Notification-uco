package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.TenantId;
import co.edu.uco.notification.utils.Preconditions;

public record GetNotificationStatusQuery(TenantId tenantId, NotificationId notificationId) {

  public GetNotificationStatusQuery {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
  }
}
