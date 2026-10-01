package co.edu.uco.notification.infrastructure.adapter.out.security.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class LocalJwtTokenValidationAdapterTest {

  private static final String SECRET = "test-only-secret-never-used-in-production-0123456789abcdef";

  private final LocalJwtTokenIssuer issuer = new LocalJwtTokenIssuer(SECRET);
  private final LocalJwtTokenValidationAdapter adapter = new LocalJwtTokenValidationAdapter(SECRET);

  @Test
  void validatesTokenIssuedByTheSameSecret() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.OPERADOR, "client-1", Duration.ofMinutes(5));

    StepVerifier.create(adapter.validate(token))
        .assertNext(
            principal -> {
              assertEquals(
                  new AuthenticatedPrincipal("client-1", TenantId.of("tenant-a"), Role.OPERADOR),
                  principal);
            })
        .verifyComplete();
  }

  @Test
  void rejectsTokenSignedWithDifferentSecret() {
    final LocalJwtTokenIssuer otherIssuer =
        new LocalJwtTokenIssuer("a-completely-different-secret-0123456789abcdef");
    final String token =
        otherIssuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));

    StepVerifier.create(adapter.validate(token)).expectError(InvalidTokenException.class).verify();
  }

  @Test
  void rejectsExpiredToken() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(-5));

    StepVerifier.create(adapter.validate(token)).expectError(InvalidTokenException.class).verify();
  }

  @Test
  void rejectsMalformedToken() {
    StepVerifier.create(adapter.validate("not-a-jwt"))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void rejectsTokenWithUnknownRole() {
    final String token =
        io.jsonwebtoken.Jwts.builder()
            .subject("client-1")
            .claim("tenantId", "tenant-a")
            .claim("role", "SUPERADMIN")
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();

    StepVerifier.create(adapter.validate(token)).expectError(InvalidTokenException.class).verify();
  }

  @Test
  void rejectsTokenMissingTenantIdClaim() {
    final String token =
        io.jsonwebtoken.Jwts.builder()
            .subject("client-1")
            .claim("role", "CLIENTE")
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();

    StepVerifier.create(adapter.validate(token)).expectError(InvalidTokenException.class).verify();
  }
}
