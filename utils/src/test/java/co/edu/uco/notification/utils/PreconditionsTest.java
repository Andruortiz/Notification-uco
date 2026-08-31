package co.edu.uco.notification.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PreconditionsTest {

  @Test
  void requireNonNullReturnsValueWhenPresent() {
    assertEquals("x", Preconditions.requireNonNull("x", "must not be null"));
  }

  @Test
  void requireNonNullThrowsWhenNull() {
    assertThrows(
        NullPointerException.class, () -> Preconditions.requireNonNull(null, "must not be null"));
  }

  @Test
  void requireNonBlankReturnsValueWhenPresent() {
    assertEquals("x", Preconditions.requireNonBlank("x", "must not be blank"));
  }

  @Test
  void requireNonBlankThrowsWhenNull() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Preconditions.requireNonBlank(null, "must not be blank"));
  }

  @Test
  void requireNonBlankThrowsWhenBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Preconditions.requireNonBlank("   ", "must not be blank"));
  }

  @Test
  void requireTrueThrowsWhenFalse() {
    assertThrows(
        IllegalArgumentException.class, () -> Preconditions.requireTrue(false, "must be true"));
  }

  @Test
  void requireTrueDoesNothingWhenTrue() {
    assertDoesNotThrow(() -> Preconditions.requireTrue(true, "must be true"));
  }
}
