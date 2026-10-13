package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record SendNotificationResult(
    NotificationId notificationId,
    NotificationStatus status,
    boolean duplicate,
    List<AttachmentSummary> attachments) {

  public SendNotificationResult {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  public SendNotificationResult(
      final NotificationId notificationId,
      final NotificationStatus status,
      final boolean duplicate) {
    this(notificationId, status, duplicate, List.of());
  }
}
