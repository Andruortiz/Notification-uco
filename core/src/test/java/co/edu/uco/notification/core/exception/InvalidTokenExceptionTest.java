package co.edu.uco.notification.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class InvalidTokenExceptionTest {

  @Test
  void defaultsToAMalformedReasonWithoutATenant() {
    final InvalidTokenException exception = new InvalidTokenException("invalid token");

    assertEquals(InvalidTokenException.Reason.MALFORMED, exception.reason());
    assertNull(exception.tenantId());
  }

  @Test
  void keepsTheCauseWhenOnlyAMessageAndACauseAreGiven() {
    final IllegalStateException cause = new IllegalStateException("boom");

    final InvalidTokenException exception = new InvalidTokenException("invalid token", cause);

    assertSame(cause, exception.getCause());
    assertEquals(InvalidTokenException.Reason.MALFORMED, exception.reason());
  }

  @Test
  void carriesTheExplicitReasonAndTheTenantOfAVerifiedToken() {
    final InvalidTokenException exception =
        new InvalidTokenException(
            "missing expiration",
            InvalidTokenException.Reason.MISSING_EXPIRATION,
            "tenant-a",
            null);

    assertEquals(InvalidTokenException.Reason.MISSING_EXPIRATION, exception.reason());
    assertEquals("tenant-a", exception.tenantId());
  }
}
