package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record DeliveryAttempt(Instant occurredOn, AttemptResult result, AttemptOrigin origin) {

  public DeliveryAttempt {
    Preconditions.requireNonNull(occurredOn, "occurredOn must not be null");
    Preconditions.requireNonNull(result, "result must not be null");
    Preconditions.requireNonNull(origin, "origin must not be null");
  }

  public static DeliveryAttempt of(
      final Instant occurredOn, final AttemptResult result, final AttemptOrigin origin) {
    return new DeliveryAttempt(occurredOn, result, origin);
  }
}
