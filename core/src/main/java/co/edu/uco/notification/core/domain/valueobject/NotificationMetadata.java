package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationMetadata(
    Instant acceptedAt,
    Long version,
    Instant pendingSince,
    Instant dispatchReservedAt,
    int currentCycle) {

  public NotificationMetadata {
    Preconditions.requireNonNull(acceptedAt, "acceptedAt must not be null");
    pendingSince = pendingSince == null ? acceptedAt : pendingSince;
    currentCycle = Math.max(currentCycle, 1);
  }

  public NotificationMetadata(final Instant acceptedAt, final Long version) {
    this(acceptedAt, version, null, null, 1);
  }
}
