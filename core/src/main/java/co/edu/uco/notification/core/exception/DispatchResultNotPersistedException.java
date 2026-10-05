package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;

public class DispatchResultNotPersistedException extends RuntimeException {

  public DispatchResultNotPersistedException(
      final NotificationId notificationId, final Throwable cause) {
    super("Dispatch result could not be persisted: " + notificationId.value(), cause);
  }
}
