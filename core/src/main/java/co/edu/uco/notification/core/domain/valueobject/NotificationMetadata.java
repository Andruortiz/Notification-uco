package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record NotificationMetadata(Instant acceptedAt, Long version) {
  public NotificationMetadata {
    Preconditions.requireNonNull(acceptedAt, "acceptedAt must not be null");
  }
}
