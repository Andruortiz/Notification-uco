package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;

public class InvalidStatusTransitionException extends RuntimeException {

  public InvalidStatusTransitionException(
      final NotificationStatus from, final NotificationStatus to) {
    super("Cannot transition from " + from + " to " + to);
  }
}
