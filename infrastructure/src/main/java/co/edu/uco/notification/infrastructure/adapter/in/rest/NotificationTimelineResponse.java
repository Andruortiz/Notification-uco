package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.in.NotificationTimelineView;
import co.edu.uco.notification.utils.LogSanitizer;
import java.time.Instant;
import java.util.List;

public record NotificationTimelineResponse(
    String notificationId,
    String status,
    String channelType,
    String recipient,
    String correlationId,
    List<Attempt> attempts) {

  public record Attempt(
      Instant occurredOn,
      String providerId,
      String result,
      String origin,
      int cycle,
      boolean failedOver,
      Long latencyMillis,
      String failureCause,
      String correlationId) {

    static Attempt from(final DeliveryAttempt attempt) {
      return new Attempt(
          attempt.occurredOn(),
          attempt.providerId().value(),
          attempt.result().name(),
          attempt.origin().name(),
          attempt.cycle(),
          attempt.failedOver(),
          attempt.latencyMillis(),
          attempt.failureCause() == null ? null : attempt.failureCause().name(),
          attempt.correlationId());
    }
  }

  static NotificationTimelineResponse from(final NotificationTimelineView view, final Role role) {
    final String address = view.recipient().address();
    return new NotificationTimelineResponse(
        view.notificationId().value(),
        view.status().name(),
        view.channelType().value(),
        role == Role.ADMINISTRADOR ? address : LogSanitizer.maskRecipient(address),
        view.correlationId() == null ? null : view.correlationId().value(),
        view.attempts().stream().map(Attempt::from).toList());
  }
}
