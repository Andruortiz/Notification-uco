package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Priority;
import org.junit.jupiter.api.Test;

class PriorityTest {

  @Test
  void weightsAreOrderedLowToHigh() {
    assertTrue(Priority.LOW.weight() < Priority.NORMAL.weight());
    assertTrue(Priority.NORMAL.weight() < Priority.HIGH.weight());
  }

  @Test
  void highHasExpectedWeight() {
    assertEquals(3, Priority.HIGH.weight());
  }
}
