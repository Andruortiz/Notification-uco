package co.edu.uco.notification.infrastructure.support;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.infrastructure.config.AuthPlatformProperties;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

public final class TestTokens {

  private static final KeyPair KEY_PAIR = generate();

  public static final String PUBLIC_KEY =
      Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());

  public static final String PRIVATE_KEY =
      Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());

  private TestTokens() {}

  public static String bearer(final String tenantId) {
    return bearer(tenantId, Role.ADMINISTRADOR);
  }

  public static String bearer(final String tenantId, final Role role) {
    return "Bearer " + issue(TenantId.of(tenantId), role, "test-client", Duration.ofMinutes(15));
  }

  public static String issue(
      final TenantId tenantId, final Role role, final String subject, final Duration ttl) {
    final Instant now = Instant.now();
    return sign(
        Jwts.builder()
            .subject(subject)
            .claim("tenantId", tenantId.value())
            .claim("role", role.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl))));
  }

  public static String sign(final JwtBuilder builder) {
    return builder.signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256).compact();
  }

  public static AuthPlatformProperties properties() {
    return new AuthPlatformProperties(PUBLIC_KEY, null, null, null, null, null, null, null, null);
  }

  private static KeyPair generate() {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
