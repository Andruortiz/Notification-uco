package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record NotificationRouting(
    TenantId tenantId,
    ExternalId externalId,
    ChannelType channelType,
    RecipientId recipientId,
    Recipient recipient) {
  public NotificationRouting {

    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(recipientId, "recipientId must not be null");
    Preconditions.requireNonNull(recipient, "recipient must not be null");
  }
}
