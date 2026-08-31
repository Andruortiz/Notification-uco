package co.edu.uco.notification.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.NotificationStatus;
import org.junit.jupiter.api.Test;

class InvalidStatusTransitionExceptionTest {

  @Test
  void messageIncludesFromAndToStatuses() {
    final InvalidStatusTransitionException exception =
        new InvalidStatusTransitionException(
            NotificationStatus.DELIVERED, NotificationStatus.PENDING);

    assertEquals("Cannot transition from DELIVERED to PENDING", exception.getMessage());
  }
}
