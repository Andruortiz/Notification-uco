package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record NotificationTimelineView(
    NotificationId notificationId,
    NotificationStatus status,
    ChannelType channelType,
    Recipient recipient,
    CorrelationId correlationId,
    List<DeliveryAttempt> attempts) {

  public NotificationTimelineView {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(recipient, "recipient must not be null");
    attempts = List.copyOf(Preconditions.requireNonNull(attempts, "attempts must not be null"));
  }
}
