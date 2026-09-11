package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.utils.Preconditions;

public record SendNotificationResult(
    NotificationId notificationId, NotificationStatus status, boolean duplicate) {

  public SendNotificationResult {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
  }
}
