package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record SearchNotificationsQuery(
    TenantId tenantId,
    RecipientId recipientId,
    ChannelType channelType,
    NotificationStatus status,
    Instant from,
    Instant to,
    int limit,
    int offset) {

  public SearchNotificationsQuery {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
  }
}
