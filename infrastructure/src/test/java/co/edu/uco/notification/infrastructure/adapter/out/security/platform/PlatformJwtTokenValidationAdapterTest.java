package co.edu.uco.notification.infrastructure.adapter.out.security.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.infrastructure.config.AuthPlatformProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class PlatformJwtTokenValidationAdapterTest {

  private static KeyPair platformKeys;
  private static KeyPair otherKeys;

  @BeforeAll
  static void generateKeys() throws NoSuchAlgorithmException {
    platformKeys = rsa();
    otherKeys = rsa();
  }

  private static KeyPair rsa() throws NoSuchAlgorithmException {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String pem(final KeyPair keys) {
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----";
  }

  private static AuthPlatformProperties properties(
      final String issuer, final String audience, final Map<String, String> roleMapping) {
    return new AuthPlatformProperties(
        pem(platformKeys), issuer, audience, null, null, null, roleMapping, null);
  }

  private static AuthPlatformProperties defaults() {
    return properties(null, null, null);
  }

  private static io.jsonwebtoken.JwtBuilder token(final KeyPair signer) {
    return Jwts.builder()
        .subject("user-1")
        .claim("tenantId", "tenant-a")
        .claim("role", "OPERADOR")
        .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
        .signWith(signer.getPrivate(), Jwts.SIG.RS256);
  }

  private static PlatformJwtTokenValidationAdapter adapter(final AuthPlatformProperties p) {
    return new PlatformJwtTokenValidationAdapter(p);
  }

  @Test
  void validatesATokenSignedByThePlatform() {
    StepVerifier.create(adapter(defaults()).validate(token(platformKeys).compact()))
        .assertNext(
            principal ->
                assertEquals(
                    new AuthenticatedPrincipal("user-1", TenantId.of("tenant-a"), Role.OPERADOR),
                    principal))
        .verifyComplete();
  }

  @Test
  void acceptsAPublicKeyWithoutPemHeaders() {
    final String bare = Base64.getEncoder().encodeToString(platformKeys.getPublic().getEncoded());
    final var adapter =
        adapter(new AuthPlatformProperties(bare, null, null, null, null, null, null, null));

    StepVerifier.create(adapter.validate(token(platformKeys).compact()))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void rejectsATokenSignedWithAnotherKey() {
    StepVerifier.create(adapter(defaults()).validate(token(otherKeys).compact()))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void rejectsATokenSignedWithAHmacKeyDerivedFromThePublicKey() {
    final String forged =
        Jwts.builder()
            .subject("attacker")
            .claim("tenantId", "tenant-a")
            .claim("role", "ADMINISTRADOR")
            .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
            .signWith(Keys.hmacShaKeyFor(platformKeys.getPublic().getEncoded()))
            .compact();

    StepVerifier.create(adapter(defaults()).validate(forged))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void rejectsAnExpiredToken() {
    final String expired =
        token(platformKeys)
            .expiration(Date.from(Instant.now().minus(Duration.ofMinutes(10))))
            .compact();

    StepVerifier.create(adapter(defaults()).validate(expired))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void rejectsAMalformedToken() {
    StepVerifier.create(adapter(defaults()).validate("not-a-jwt"))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void requiresTheConfiguredIssuer() {
    final var adapter = adapter(properties("https://idp.example", null, null));

    StepVerifier.create(
            adapter.validate(token(platformKeys).issuer("https://idp.example").compact()))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(adapter.validate(token(platformKeys).issuer("https://evil").compact()))
        .expectError(InvalidTokenException.class)
        .verify();
    StepVerifier.create(adapter.validate(token(platformKeys).compact()))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void requiresTheConfiguredAudience() {
    final var adapter = adapter(properties(null, "notification-uco", null));

    StepVerifier.create(
            adapter.validate(
                token(platformKeys).audience().add("notification-uco").and().compact()))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            adapter.validate(token(platformKeys).audience().add("other").and().compact()))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void readsClaimsWithTheConfiguredNames() {
    final var adapter =
        adapter(
            new AuthPlatformProperties(
                pem(platformKeys), null, null, "preferred_username", "org", "roles", null, null));
    final String token =
        Jwts.builder()
            .claim("preferred_username", "maria")
            .claim("org", "tenant-z")
            .claim("roles", "CLIENTE")
            .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
            .signWith(platformKeys.getPrivate(), Jwts.SIG.RS256)
            .compact();

    StepVerifier.create(adapter.validate(token))
        .assertNext(
            principal ->
                assertEquals(
                    new AuthenticatedPrincipal("maria", TenantId.of("tenant-z"), Role.CLIENTE),
                    principal))
        .verifyComplete();
  }

  @Test
  void translatesPlatformRolesWithTheMappingTable() {
    final var adapter =
        adapter(properties(null, null, Map.of("soporte", "OPERADOR", "sistema", "CLIENTE")));

    StepVerifier.create(adapter.validate(token(platformKeys).claim("role", "soporte").compact()))
        .assertNext(principal -> assertEquals(Role.OPERADOR, principal.role()))
        .verifyComplete();
  }

  @Test
  void picksTheHighestPrivilegeWhenTheTokenCarriesSeveralRoles() {
    final var adapter =
        adapter(properties(null, null, Map.of("admin-plataforma", "ADMINISTRADOR")));
    final String token =
        token(platformKeys)
            .claim("role", List.of("CLIENTE", "desconocido", "admin-plataforma"))
            .compact();

    StepVerifier.create(adapter.validate(token))
        .assertNext(principal -> assertEquals(Role.ADMINISTRADOR, principal.role()))
        .verifyComplete();
  }

  @Test
  void rejectsAnUnknownRole() {
    StepVerifier.create(
            adapter(defaults()).validate(token(platformKeys).claim("role", "invitado").compact()))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void rejectsATokenWithoutTenantOrSubject() {
    final String withoutTenant =
        Jwts.builder()
            .subject("user-1")
            .claim("role", "OPERADOR")
            .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
            .signWith(platformKeys.getPrivate(), Jwts.SIG.RS256)
            .compact();

    StepVerifier.create(adapter(defaults()).validate(withoutTenant))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void failsAtStartupWithoutAPublicKey() {
    final var blank = new AuthPlatformProperties(" ", null, null, null, null, null, null, null);

    assertThrows(IllegalStateException.class, () -> new PlatformJwtTokenValidationAdapter(blank));
  }

  @Test
  void failsAtStartupWithAnInvalidPublicKey() {
    final var invalid =
        new AuthPlatformProperties("no-es-una-clave", null, null, null, null, null, null, null);

    assertThrows(IllegalStateException.class, () -> new PlatformJwtTokenValidationAdapter(invalid));
  }
}
