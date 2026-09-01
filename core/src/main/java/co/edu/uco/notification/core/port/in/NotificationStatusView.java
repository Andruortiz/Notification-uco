package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.NotificationStatus;
import co.edu.uco.notification.core.domain.ProviderId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationStatusView(
    NotificationId notificationId,
    NotificationStatus status,
    ChannelType channelType,
    ProviderId lastProviderId,
    Instant lastUpdatedAt) {

  public NotificationStatusView {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(lastUpdatedAt, "lastUpdatedAt must not be null");
  }
}
