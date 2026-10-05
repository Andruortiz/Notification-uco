package co.edu.uco.notification.infrastructure.adapter.out.security.local;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.JwtFailures;
import co.edu.uco.notification.infrastructure.config.AuthJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@DependsOn("authJwtSecretGuard")
@ConditionalOnProperty(
    name = "notification.auth.mode",
    havingValue = "local",
    matchIfMissing = true)
public final class LocalJwtTokenValidationAdapter implements TokenValidationPort {

  private static final String TENANT_CLAIM = "tenantId";
  private static final String ROLE_CLAIM = "role";

  private final SecretKey key;

  public LocalJwtTokenValidationAdapter(final String secret) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  @Autowired
  public LocalJwtTokenValidationAdapter(final AuthJwtProperties properties) {
    this(properties.hs256Secret());
  }

  @Override
  public Mono<AuthenticatedPrincipal> validate(final String rawToken) {
    return Mono.fromCallable(() -> parse(rawToken));
  }

  private AuthenticatedPrincipal parse(final String rawToken) {
    final Claims claims = parseClaims(rawToken);
    final String tenantHint = JwtFailures.tenantOf(claims, TENANT_CLAIM);
    if (claims.getExpiration() == null) {
      throw new InvalidTokenException(
          "missing expiration", InvalidTokenException.Reason.MISSING_EXPIRATION, tenantHint, null);
    }
    final String subject = claims.getSubject();
    if (isBlank(subject) || tenantHint == null) {
      throw new InvalidTokenException(
          "missing required claims", InvalidTokenException.Reason.MISSING_CLAIMS, tenantHint, null);
    }
    final String roleValue = roleOf(claims, tenantHint);
    return new AuthenticatedPrincipal(
        subject, TenantId.of(tenantHint), parseRole(roleValue, tenantHint));
  }

  private Claims parseClaims(final String rawToken) {
    try {
      return Jwts.parser().verifyWith(key).build().parseSignedClaims(rawToken).getPayload();
    } catch (final JwtException | IllegalArgumentException e) {
      throw JwtFailures.toInvalidToken(e, TENANT_CLAIM);
    }
  }

  private static String roleOf(final Claims claims, final String tenantHint) {
    final Object value = claims.get(ROLE_CLAIM);
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    throw new InvalidTokenException(
        "missing required claims", InvalidTokenException.Reason.MISSING_CLAIMS, tenantHint, null);
  }

  private static Role parseRole(final String roleValue, final String tenantHint) {
    try {
      return Role.of(roleValue);
    } catch (final IllegalArgumentException e) {
      throw new InvalidTokenException(
          "unknown role", InvalidTokenException.Reason.UNKNOWN_ROLE, tenantHint, e);
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
