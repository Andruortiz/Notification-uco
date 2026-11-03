package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;

public record ResendNotificationCommand(
    TenantId tenantId,
    NotificationId notificationId,
    ChannelType channelType,
    Recipient recipient,
    CorrelationId correlationId) {

  public ResendNotificationCommand {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(recipient, "recipient must not be null");
  }
}
