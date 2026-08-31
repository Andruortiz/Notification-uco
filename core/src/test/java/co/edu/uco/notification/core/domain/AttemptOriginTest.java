package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AttemptOriginTest {

  @Test
  void hasExactlyTheTwoExpectedValues() {
    assertEquals(2, AttemptOrigin.values().length);
  }
}
