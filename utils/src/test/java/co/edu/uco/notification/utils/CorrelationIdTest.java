package co.edu.uco.notification.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CorrelationIdTest {

  @Test
  void acceptsAWellFormedValue() {
    assertEquals("abc-123_x.y", CorrelationId.of("abc-123_x.y").value());
  }

  @Test
  void rejectsBlankAndMalformedValues() {
    assertThrows(IllegalArgumentException.class, () -> CorrelationId.of(" "));
    assertThrows(IllegalArgumentException.class, () -> CorrelationId.of("a b"));
    assertThrows(IllegalArgumentException.class, () -> CorrelationId.of("a\nb"));
    assertThrows(IllegalArgumentException.class, () -> CorrelationId.of("x".repeat(65)));
  }

  @Test
  void generatesDistinctIds() {
    assertNotEquals(CorrelationId.newId(), CorrelationId.newId());
  }

  @Test
  void fromOrNewKeepsAValidCandidateAndReplacesAnInvalidOne() {
    assertEquals("req-1", CorrelationId.fromOrNew(" req-1 ").value());
    assertNotNull(CorrelationId.fromOrNew("bad value\n"));
    assertNotEquals("bad value\n", CorrelationId.fromOrNew("bad value\n").value());
    assertNotNull(CorrelationId.fromOrNew(null));
  }

  @Test
  void fromOrNullReturnsNullForInvalidCandidates() {
    assertNull(CorrelationId.fromOrNull(null));
    assertNull(CorrelationId.fromOrNull("bad value"));
    assertEquals("ok", CorrelationId.fromOrNull("ok").value());
  }
}
