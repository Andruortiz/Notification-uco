package co.edu.uco.notification.infrastructure.adapter.out.security.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.util.Date;
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

  @Test
  void rejectsATokenWithoutExpirationAndReportsTheReasonAndTheTenant() {
    final String token =
        Jwts.builder()
            .subject("client-1")
            .claim("tenantId", "tenant-a")
            .claim("role", "CLIENTE")
            .issuedAt(new Date())
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();

    StepVerifier.create(adapter.validate(token))
        .expectErrorSatisfies(
            error ->
                assertRejected(error, InvalidTokenException.Reason.MISSING_EXPIRATION, "tenant-a"))
        .verify();
  }

  @Test
  void rejectsAnUnsignedToken() {
    final String token =
        Jwts.builder()
            .subject("client-1")
            .claim("tenantId", "tenant-a")
            .claim("role", "CLIENTE")
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .compact();

    StepVerifier.create(adapter.validate(token)).expectError(InvalidTokenException.class).verify();
  }

  @Test
  void rejectsATokenThatIsNotValidYet() {
    final String token =
        Jwts.builder()
            .subject("client-1")
            .claim("tenantId", "tenant-a")
            .claim("role", "CLIENTE")
            .notBefore(new Date(System.currentTimeMillis() + 3_600_000))
            .expiration(new Date(System.currentTimeMillis() + 7_200_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();

    StepVerifier.create(adapter.validate(token))
        .expectErrorSatisfies(
            error -> assertRejected(error, InvalidTokenException.Reason.NOT_YET_VALID, "tenant-a"))
        .verify();
  }

  @Test
  void reportsTheExpiredReasonTogetherWithTheTenantOfTheToken() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(-5));

    StepVerifier.create(adapter.validate(token))
        .expectErrorSatisfies(
            error -> assertRejected(error, InvalidTokenException.Reason.EXPIRED, "tenant-a"))
        .verify();
  }

  @Test
  void reportsMissingClaimsWhenTheTenantClaimHasTheWrongType() {
    final String token =
        Jwts.builder()
            .subject("client-1")
            .claim("tenantId", 42)
            .claim("role", "CLIENTE")
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();

    StepVerifier.create(adapter.validate(token))
        .expectErrorSatisfies(
            error -> assertRejected(error, InvalidTokenException.Reason.MISSING_CLAIMS, null))
        .verify();
  }

  @Test
  void reportsAnUnknownRoleTogetherWithTheTenantOfTheToken() {
    final String token =
        Jwts.builder()
            .subject("client-1")
            .claim("tenantId", "tenant-a")
            .claim("role", "SUPERADMIN")
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();

    StepVerifier.create(adapter.validate(token))
        .expectErrorSatisfies(
            error -> assertRejected(error, InvalidTokenException.Reason.UNKNOWN_ROLE, "tenant-a"))
        .verify();
  }

  private static void assertRejected(
      final Throwable error, final InvalidTokenException.Reason reason, final String tenantId) {
    assertInstanceOf(InvalidTokenException.class, error);
    assertEquals(reason, ((InvalidTokenException) error).reason());
    assertEquals(tenantId, ((InvalidTokenException) error).tenantId());
  }
}
