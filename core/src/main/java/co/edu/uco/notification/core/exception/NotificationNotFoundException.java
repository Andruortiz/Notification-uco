package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;

public class NotificationNotFoundException extends RuntimeException {

  public NotificationNotFoundException(final NotificationId notificationId) {
    super("Notification not found: " + notificationId.value());
  }
}
