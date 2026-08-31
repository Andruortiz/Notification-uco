package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.Priority;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import co.edu.uco.notification.core.domain.TenantId;
import co.edu.uco.notification.utils.Preconditions;

public record SendNotificationCommand(
    TenantId tenantId,
    ExternalId externalId,
    ChannelType channelType,
    RecipientId recipientId,
    Recipient recipient,
    NotificationContent content,
    Priority priority) {

  public SendNotificationCommand {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(recipientId, "recipientId must not be null");
    Preconditions.requireNonNull(recipient, "recipient must not be null");
    Preconditions.requireNonNull(content, "content must not be null");
    Preconditions.requireNonNull(priority, "priority must not be null");
  }
}
