package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record UndeliverableRecipient(
    TenantId tenantId,
    ChannelType channelType,
    String normalizedAddress,
    String reason,
    Instant markedAt) {

  public UndeliverableRecipient {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonBlank(normalizedAddress, "normalizedAddress must not be blank");
    Preconditions.requireNonBlank(reason, "reason must not be blank");
    Preconditions.requireNonNull(markedAt, "markedAt must not be null");
  }
}
