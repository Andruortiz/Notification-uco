package co.edu.uco.notification.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

  @Test
  void masksEmailKeepingOnlyTheDomain() {
    assertEquals("***@ejemplo.com", LogSanitizer.maskRecipient("usuario@ejemplo.com"));
  }

  @Test
  void masksPhoneNumbersKeepingTheLastDigits() {
    assertEquals("***5678", LogSanitizer.maskRecipient("+573001235678"));
  }

  @Test
  void masksOpaqueTokensKeepingTheLastCharacters() {
    assertEquals("***wxyz", LogSanitizer.maskRecipient("fcm-token-abcdefwxyz"));
    assertEquals("***", LogSanitizer.maskRecipient("abc"));
  }

  @Test
  void masksNullAndBlankAddresses() {
    assertEquals("***", LogSanitizer.maskRecipient(null));
    assertEquals("***", LogSanitizer.maskRecipient("  "));
  }

  @Test
  void maskedRecipientNeverContainsTheLocalPart() {
    assertFalse(LogSanitizer.maskRecipient("usuario@ejemplo.com").contains("usuario"));
  }

  @Test
  void redactNeverEchoesTheValue() {
    assertEquals(LogSanitizer.REDACTED, LogSanitizer.redact("super-secret"));
    assertEquals(LogSanitizer.REDACTED, LogSanitizer.redact(null));
  }

  @Test
  void safeNeutralizesControlCharactersAndLimitsLength() {
    assertEquals("a_b_c", LogSanitizer.safe("a\nb\rc"));
    assertEquals("null", LogSanitizer.safe(null));
    assertEquals(LogSanitizer.MAX_LENGTH, LogSanitizer.safe("x".repeat(1000)).length());
  }

  @Test
  void redactSecretsReplacesEveryOccurrenceAndIgnoresEmptySecrets() {
    final String redacted =
        LogSanitizer.redactSecrets("key=abc123 and abc123", "abc123", null, " ");
    assertEquals("key=[REDACTED] and [REDACTED]", redacted);
    assertTrue(LogSanitizer.redactSecrets(null, "x") == null);
  }
}
