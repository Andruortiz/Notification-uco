package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;

public record MessageRecipientRef(
    TenantId tenantId, ChannelType channelType, String address, NotificationId notificationId) {

  public MessageRecipientRef {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonBlank(address, "address must not be blank");
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
  }
}
