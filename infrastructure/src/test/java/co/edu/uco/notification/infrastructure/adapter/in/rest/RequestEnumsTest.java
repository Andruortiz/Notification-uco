package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.Priority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RequestEnumsTest {

  @Test
  void requiredParsesAKnownValue() {
    assertEquals(Priority.HIGH, RequestEnums.required(Priority.class, "priority", "HIGH"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void requiredRejectsABlankValue(final String value) {
    final IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestEnums.required(Priority.class, "priority", value));

    assertEquals("priority must not be blank", error.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"normal", "URGENT", " NORMAL"})
  void requiredNamesTheAllowedValuesWhenTheValueIsUnknown(final String value) {
    final IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestEnums.required(Priority.class, "priority", value));

    assertEquals("priority must be one of LOW, NORMAL, HIGH", error.getMessage());
  }

  @Test
  void optionalReturnsNullWhenTheValueIsAbsent() {
    assertNull(RequestEnums.optional(Priority.class, "priority", null));
  }

  @Test
  void optionalParsesAKnownValue() {
    assertEquals(Priority.LOW, RequestEnums.optional(Priority.class, "priority", "LOW"));
  }

  @Test
  void optionalNamesTheAllowedValuesWhenTheValueIsUnknown() {
    final IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestEnums.optional(Priority.class, "priority", "nope"));

    assertEquals("priority must be one of LOW, NORMAL, HIGH", error.getMessage());
  }
}
