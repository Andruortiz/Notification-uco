package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.List;

public record NotificationSearchResult(
    NotificationId notificationId,
    ExternalId externalId,
    RecipientId recipientId,
    ChannelType channelType,
    NotificationStatus status,
    Instant acceptedAt,
    List<DeliveryAttempt> deliveryAttempts) {

  public NotificationSearchResult {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(recipientId, "recipientId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireNonNull(acceptedAt, "acceptedAt must not be null");
    Preconditions.requireNonNull(deliveryAttempts, "deliveryAttempts must not be null");
    deliveryAttempts = List.copyOf(deliveryAttempts);
  }
}
