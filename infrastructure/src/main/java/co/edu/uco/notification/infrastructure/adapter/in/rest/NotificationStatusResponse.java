package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.NotificationStatusView;
import java.time.Instant;

public record NotificationStatusResponse(
    String notificationId,
    String status,
    String channelType,
    String providerId,
    Instant lastUpdatedAt) {

  static NotificationStatusResponse from(final NotificationStatusView view) {
    return new NotificationStatusResponse(
        view.notificationId().value(),
        view.status().name(),
        view.channelType().value(),
        view.lastProviderId() == null ? null : view.lastProviderId().value(),
        view.lastUpdatedAt());
  }
}
