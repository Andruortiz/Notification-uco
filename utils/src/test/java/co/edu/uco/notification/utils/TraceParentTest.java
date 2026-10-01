package co.edu.uco.notification.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TraceParentTest {

  private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Test
  void acceptsAWellFormedValue() {
    assertEquals(VALID, TraceParent.of(VALID).value());
  }

  @Test
  void rejectsMalformedValues() {
    assertThrows(IllegalArgumentException.class, () -> TraceParent.of(" "));
    assertThrows(IllegalArgumentException.class, () -> TraceParent.of("00-abc-def-01"));
    assertThrows(IllegalArgumentException.class, () -> TraceParent.of(VALID + "\nx"));
    assertThrows(IllegalArgumentException.class, () -> TraceParent.of(VALID.toUpperCase()));
  }

  @Test
  void fromOrNullTrimsValidAndDropsInvalidCandidates() {
    assertEquals(VALID, TraceParent.fromOrNull("  " + VALID + " ").value());
    assertNull(TraceParent.fromOrNull(null));
    assertNull(TraceParent.fromOrNull("garbage"));
  }
}
