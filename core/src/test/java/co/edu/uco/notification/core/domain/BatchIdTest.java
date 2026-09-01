package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BatchIdTest {

  @Test
  void ofPreservesGivenValue() {
    assertEquals("batch-42", BatchId.of("batch-42").value());
  }

  @Test
  void newIdGeneratesDistinctValues() {
    assertNotEquals(BatchId.newId(), BatchId.newId());
  }

  @Test
  void rejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> BatchId.of("   "));
  }

  @Test
  void rejectsNullValue() {
    assertThrows(IllegalArgumentException.class, () -> BatchId.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(BatchId.of("batch-42"), BatchId.of("batch-42"));
  }
}
