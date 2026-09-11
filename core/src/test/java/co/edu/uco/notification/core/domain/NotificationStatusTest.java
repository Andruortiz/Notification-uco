package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import org.junit.jupiter.api.Test;

class NotificationStatusTest {

  @Test
  void hasExactlyTheSixExpectedValues() {
    assertEquals(6, NotificationStatus.values().length);
  }
}
