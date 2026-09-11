package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;

public class NotificationVersionConflictException extends RuntimeException {

  public NotificationVersionConflictException(final NotificationId notificationId) {
    super("Concurrent modification of notification: " + notificationId.value());
  }
}
