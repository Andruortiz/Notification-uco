package co.edu.uco.notification.infrastructure.adapter.out.security;

import co.edu.uco.notification.core.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.InvalidClaimException;
import io.jsonwebtoken.PrematureJwtException;
import io.jsonwebtoken.security.SignatureException;

public final class JwtFailures {

  private JwtFailures() {}

  public static InvalidTokenException toInvalidToken(
      final Exception failure, final String tenantClaim) {
    if (failure instanceof ExpiredJwtException expired) {
      return rejected(
          "expired token",
          InvalidTokenException.Reason.EXPIRED,
          tenantOf(expired.getClaims(), tenantClaim),
          failure);
    }
    if (failure instanceof PrematureJwtException premature) {
      return rejected(
          "token not valid yet",
          InvalidTokenException.Reason.NOT_YET_VALID,
          tenantOf(premature.getClaims(), tenantClaim),
          failure);
    }
    if (failure instanceof SignatureException) {
      return rejected(
          "invalid signature", InvalidTokenException.Reason.INVALID_SIGNATURE, null, failure);
    }
    if (failure instanceof InvalidClaimException invalid) {
      return rejected(
          "invalid claims",
          InvalidTokenException.Reason.MISSING_CLAIMS,
          tenantOf(invalid.getClaims(), tenantClaim),
          failure);
    }
    return rejected("invalid token", InvalidTokenException.Reason.MALFORMED, null, failure);
  }

  public static String tenantOf(final Claims claims, final String tenantClaim) {
    if (claims == null) {
      return null;
    }
    final Object value = claims.get(tenantClaim);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private static InvalidTokenException rejected(
      final String message,
      final InvalidTokenException.Reason reason,
      final String tenantId,
      final Exception cause) {
    return new InvalidTokenException(message, reason, tenantId, cause);
  }
}
