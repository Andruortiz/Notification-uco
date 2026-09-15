package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.utils.Preconditions;

public record NotificationLiveUpdate(
    NotificationSearchResult notification, LiveUpdateAction action) {

  public NotificationLiveUpdate {
    Preconditions.requireNonNull(notification, "notification must not be null");
    Preconditions.requireNonNull(action, "action must not be null");
  }
}
