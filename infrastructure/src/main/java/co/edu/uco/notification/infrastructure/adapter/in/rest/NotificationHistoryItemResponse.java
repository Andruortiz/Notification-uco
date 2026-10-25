package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.policy.TokenMask;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.in.NotificationSearchResult;
import java.time.Instant;
import java.util.List;

public record NotificationHistoryItemResponse(
    String notificationId,
    String externalId,
    String recipientId,
    String channelType,
    String status,
    Instant acceptedAt,
    List<DeliveryAttemptResponse> deliveryAttempts) {

  public NotificationHistoryItemResponse {
    deliveryAttempts = List.copyOf(deliveryAttempts);
  }

  static NotificationHistoryItemResponse from(
      final NotificationSearchResult result, final Role role) {
    return new NotificationHistoryItemResponse(
        result.notificationId().value(),
        result.externalId().value(),
        visibleRecipient(result, role),
        result.channelType().value(),
        result.status().name(),
        result.acceptedAt(),
        result.deliveryAttempts().stream().map(DeliveryAttemptResponse::from).toList());
  }

  private static String visibleRecipient(final NotificationSearchResult result, final Role role) {
    final String recipientId = result.recipientId().value();
    return "PUSH".equals(result.channelType().value()) && role != Role.ADMINISTRADOR
        ? TokenMask.mask(recipientId)
        : recipientId;
  }
}
