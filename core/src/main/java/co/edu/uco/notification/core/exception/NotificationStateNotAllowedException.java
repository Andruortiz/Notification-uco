package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;

public class NotificationStateNotAllowedException extends RuntimeException {

  public NotificationStateNotAllowedException(final NotificationStatus status) {
    super("The notification state does not allow this operation: " + status);
  }
}
