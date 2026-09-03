package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.SendNotificationResult;

public record SendNotificationResponse(String notificationId, String status, boolean duplicate) {

  static SendNotificationResponse from(final SendNotificationResult result) {
    return new SendNotificationResponse(
        result.notificationId().value(), result.status().name(), result.duplicate());
  }
}
