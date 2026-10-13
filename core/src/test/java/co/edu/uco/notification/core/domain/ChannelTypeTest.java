package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import org.junit.jupiter.api.Test;

class ChannelTypeTest {

  @Test
  void ofPreservesGivenValue() {
    assertEquals("EMAIL", ChannelType.of("EMAIL").value());
  }

  @Test
  void rejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> ChannelType.of("   "));
  }

  @Test
  void rejectsNullValue() {
    assertThrows(IllegalArgumentException.class, () -> ChannelType.of(null));
  }

  @Test
  void equalityIsByValue() {
    assertEquals(ChannelType.of("EMAIL"), ChannelType.of("EMAIL"));
  }

  @Test
  void isNotAJavaEnum() {
    assertFalse(ChannelType.class.isEnum());
  }
}
