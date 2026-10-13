package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import java.time.Instant;

public record DeliveryAttemptResponse(
    Instant occurredOn, String result, String origin, String providerId) {

  static DeliveryAttemptResponse from(final DeliveryAttempt attempt) {
    return new DeliveryAttemptResponse(
        attempt.occurredOn(),
        attempt.result().name(),
        attempt.origin().name(),
        attempt.providerId().value());
  }
}
