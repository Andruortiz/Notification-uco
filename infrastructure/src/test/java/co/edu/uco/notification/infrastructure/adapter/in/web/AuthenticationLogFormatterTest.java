package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthenticationLogFormatterTest {

  @Test
  void formatsRejectionWithKnownTenant() {
    final String line = AuthenticationLogFormatter.rejection("tenant-a", RejectionReason.EXPIRED);

    assertTrue(line.contains("tenant-a"));
    assertTrue(line.contains("EXPIRED"));
  }

  @Test
  void formatsRejectionWithNullTenantWithoutThrowing() {
    assertDoesNotThrow(
        () -> AuthenticationLogFormatter.rejection(null, RejectionReason.MISSING_TOKEN));
  }

  @Test
  void safeSanitizesControlCharacters() {
    final String sanitized = AuthenticationLogFormatter.safe("tenant\na\tb");

    assertFalse(sanitized.contains("\n"));
    assertFalse(sanitized.contains("\t"));
  }

  @Test
  void safeReturnsNullLiteralForNullValue() {
    assertEquals("null", AuthenticationLogFormatter.safe(null));
  }
}
