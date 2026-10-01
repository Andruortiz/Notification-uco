package co.edu.uco.notification.infrastructure.adapter.out.security.local;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

public final class LocalJwtTokenIssuer {

  private final SecretKey key;

  public LocalJwtTokenIssuer(final String secret) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  public String issue(
      final TenantId tenantId, final Role role, final String subject, final Duration ttl) {
    final Instant now = Instant.now();
    return Jwts.builder()
        .subject(subject)
        .claim("tenantId", tenantId.value())
        .claim("role", role.name())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(key)
        .compact();
  }
}
