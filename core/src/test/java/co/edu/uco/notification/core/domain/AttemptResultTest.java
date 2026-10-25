package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import org.junit.jupiter.api.Test;

class AttemptResultTest {

  @Test
  void hasExactlyTheFourExpectedValues() {
    assertEquals(4, AttemptResult.values().length);
  }
}
