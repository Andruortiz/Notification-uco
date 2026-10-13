package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationStatusView(
    NotificationId notificationId,
    NotificationStatus status,
    ChannelType channelType,
    ProviderId lastProviderId,
    Instant lastUpdatedAt,
    CorrelationId correlationId) {

  public NotificationStatusView {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(lastUpdatedAt, "lastUpdatedAt must not be null");
  }

  public NotificationStatusView(
      final NotificationId notificationId,
      final NotificationStatus status,
      final ChannelType channelType,
      final ProviderId lastProviderId,
      final Instant lastUpdatedAt) {
    this(notificationId, status, channelType, lastProviderId, lastUpdatedAt, null);
  }
}
