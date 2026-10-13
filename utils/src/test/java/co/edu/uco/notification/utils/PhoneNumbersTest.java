package co.edu.uco.notification.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

  @Test
  void acceptsInternationalNumbers() {
    assertTrue(PhoneNumbers.isE164("+573001234567"));
    assertTrue(PhoneNumbers.isE164("+15005550006"));
    assertTrue(PhoneNumbers.isE164("+12"));
    assertTrue(PhoneNumbers.isE164("+123456789012345"));
  }

  @Test
  void rejectsValuesThatAreNotInternationalNumbers() {
    assertFalse(PhoneNumbers.isE164(null));
    assertFalse(PhoneNumbers.isE164(""));
    assertFalse(PhoneNumbers.isE164("   "));
    assertFalse(PhoneNumbers.isE164("alice@example.com"));
    assertFalse(PhoneNumbers.isE164("3001234567"));
    assertFalse(PhoneNumbers.isE164("+0573001234567"));
    assertFalse(PhoneNumbers.isE164("+1234567890123456"));
    assertFalse(PhoneNumbers.isE164("+57 300 123 4567"));
    assertFalse(PhoneNumbers.isE164("+57300abc4567"));
    assertFalse(PhoneNumbers.isE164("+1"));
  }

  @Test
  void masksAllButTheLastFourDigits() {
    assertEquals("***4567", PhoneNumbers.mask("+573001234567"));
    assertEquals("***0006", PhoneNumbers.mask("+15005550006"));
    assertEquals("***2345", PhoneNumbers.mask("+12345"));
  }

  @Test
  void masksCompletelyWhenThereAreFourDigitsOrLess() {
    assertEquals("***", PhoneNumbers.mask(null));
    assertEquals("***", PhoneNumbers.mask(""));
    assertEquals("***", PhoneNumbers.mask("+1234"));
    assertEquals("***", PhoneNumbers.mask("12"));
    assertEquals("***", PhoneNumbers.mask("alice@example.com"));
  }
}
