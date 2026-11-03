package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.policy.TokenMask;
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
      final NotificationSearchResult result, final boolean unmasked) {
    return new NotificationHistoryItemResponse(
        result.notificationId().value(),
        result.externalId().value(),
        visibleRecipient(result, unmasked),
        result.channelType().value(),
        result.status().name(),
        result.acceptedAt(),
        result.deliveryAttempts().stream().map(DeliveryAttemptResponse::from).toList());
  }

  private static String visibleRecipient(
      final NotificationSearchResult result, final boolean unmasked) {
    final String recipientId = result.recipientId().value();
    return "PUSH".equals(result.channelType().value()) && !unmasked
        ? TokenMask.mask(recipientId)
        : recipientId;
  }
}
