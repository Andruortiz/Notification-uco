package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.List;

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

  public static NotificationStatusView from(final Notification notification) {
    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    final DeliveryAttempt lastAttempt = attempts.isEmpty() ? null : attempts.getLast();
    return new NotificationStatusView(
        notification.notificationId(),
        notification.status(),
        notification.channelType(),
        lastAttempt == null ? null : lastAttempt.providerId(),
        lastAttempt == null ? notification.acceptedAt() : lastAttempt.occurredOn(),
        notification.correlationId());
  }
}
