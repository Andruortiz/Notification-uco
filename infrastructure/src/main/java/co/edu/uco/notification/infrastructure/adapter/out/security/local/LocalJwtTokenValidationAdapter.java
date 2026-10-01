package co.edu.uco.notification.infrastructure.adapter.out.security.local;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.config.AuthJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class LocalJwtTokenValidationAdapter implements TokenValidationPort {

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
    final String subject = claims.getSubject();
    final String tenantIdValue = claims.get("tenantId", String.class);
    final String roleValue = claims.get("role", String.class);
    if (isBlank(subject) || isBlank(tenantIdValue) || isBlank(roleValue)) {
      throw new InvalidTokenException("missing required claims", new IllegalStateException());
    }
    return new AuthenticatedPrincipal(subject, TenantId.of(tenantIdValue), parseRole(roleValue));
  }

  private Claims parseClaims(final String rawToken) {
    try {
      return Jwts.parser().verifyWith(key).build().parseSignedClaims(rawToken).getPayload();
    } catch (final JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException("invalid token", e);
    }
  }

  private Role parseRole(final String roleValue) {
    try {
      return Role.of(roleValue);
    } catch (final IllegalArgumentException e) {
      throw new InvalidTokenException("unknown role", e);
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
