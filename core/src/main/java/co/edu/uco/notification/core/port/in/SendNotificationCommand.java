package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record SendNotificationCommand(
    TenantId tenantId,
    ExternalId externalId,
    ChannelType channelType,
    RecipientId recipientId,
    Recipient recipient,
    NotificationContent content,
    Priority priority,
    List<AttachmentSubmission> attachments) {

  public SendNotificationCommand {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(recipientId, "recipientId must not be null");
    Preconditions.requireNonNull(recipient, "recipient must not be null");
    Preconditions.requireNonNull(content, "content must not be null");
    Preconditions.requireNonNull(priority, "priority must not be null");
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  public SendNotificationCommand(
      final TenantId tenantId,
      final ExternalId externalId,
      final ChannelType channelType,
      final RecipientId recipientId,
      final Recipient recipient,
      final NotificationContent content,
      final Priority priority) {
    this(tenantId, externalId, channelType, recipientId, recipient, content, priority, List.of());
  }
}
