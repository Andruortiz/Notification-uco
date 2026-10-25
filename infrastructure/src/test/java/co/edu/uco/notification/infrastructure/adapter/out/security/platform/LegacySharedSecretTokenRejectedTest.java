package co.edu.uco.notification.infrastructure.adapter.out.security.platform;

import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class LegacySharedSecretTokenRejectedTest {

  private static final String LEGACY_SECRET =
      "notification-uco-dev-only-secret-change-me-0123456789abcdef";

  @Test
  void aTokenSignedWithASharedSecretIsRejectedByThePlatformValidator() {
    final String legacy =
        Jwts.builder()
            .subject("test-client")
            .claim("tenantId", "tenant-a")
            .claim("role", "ADMINISTRADOR")
            .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
            .signWith(Keys.hmacShaKeyFor(LEGACY_SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

    StepVerifier.create(
            new PlatformJwtTokenValidationAdapter(TestTokens.properties()).validate(legacy))
        .expectError(InvalidTokenException.class)
        .verify();
  }
}
