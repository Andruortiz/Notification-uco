package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationSearchCriteria(
    TenantId tenantId,
    RecipientId recipientId,
    ChannelType channelType,
    NotificationStatus status,
    Instant from,
    Instant to,
    int limit,
    int offset) {

  public NotificationSearchCriteria {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireTrue(limit >= 1 && limit <= 200, "limit must be between 1 and 200");
    Preconditions.requireTrue(offset >= 0, "offset must not be negative");
    Preconditions.requireTrue(
        from == null || to == null || !from.isAfter(to), "from must not be after to");
  }
}
