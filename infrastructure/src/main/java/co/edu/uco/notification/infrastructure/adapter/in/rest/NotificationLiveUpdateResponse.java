package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.NotificationLiveUpdate;

public record NotificationLiveUpdateResponse(
    String action, NotificationHistoryItemResponse notification) {

  static NotificationLiveUpdateResponse from(final NotificationLiveUpdate update) {
    return new NotificationLiveUpdateResponse(
        update.action().name(), NotificationHistoryItemResponse.from(update.notification()));
  }
}
