package co.edu.uco.notification.infrastructure.adapter.out.security.platform;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.config.AuthPlatformProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Valida tokens emitidos por la plataforma central de seguridad: la firma se comprueba con la clave
 * publica del emisor (nunca con un secreto compartido), y el emisor y la audiencia, si se
 * configuran, tienen que coincidir. Los datos del token se traducen a {@link
 * AuthenticatedPrincipal} segun los nombres de claims y la tabla de roles configurados.
 *
 * <p>Se activa con {@code notification.auth.mode=platform}.
 */
@Component
@ConditionalOnProperty(name = "notification.auth.mode", havingValue = "platform")
public final class PlatformJwtTokenValidationAdapter implements TokenValidationPort {

  private final JwtParser parser;
  private final AuthPlatformProperties properties;

  public PlatformJwtTokenValidationAdapter(final AuthPlatformProperties properties) {
    this.properties = properties;
    this.parser = buildParser(properties);
  }

  @Override
  public Mono<AuthenticatedPrincipal> validate(final String rawToken) {
    return Mono.fromCallable(() -> toPrincipal(parseClaims(rawToken)));
  }

  private static JwtParser buildParser(final AuthPlatformProperties properties) {
    final var builder =
        Jwts.parser()
            .verifyWith(parsePublicKey(properties.publicKey()))
            .clockSkewSeconds(properties.clockSkewSeconds());
    if (hasText(properties.issuer())) {
      builder.requireIssuer(properties.issuer().trim());
    }
    if (hasText(properties.audience())) {
      builder.requireAudience(properties.audience().trim());
    }
    return builder.build();
  }

  private static PublicKey parsePublicKey(final String pem) {
    if (!hasText(pem)) {
      throw new IllegalStateException(
          "notification.auth.mode=platform requires notification.auth.platform.public-key");
    }
    final String base64 =
        pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\n", "")
            .replaceAll("\\s", "");
    final byte[] der;
    try {
      der = Base64.getDecoder().decode(base64);
    } catch (final IllegalArgumentException e) {
      throw new IllegalStateException("notification.auth.platform.public-key is not valid", e);
    }
    for (final String algorithm : List.of("RSA", "EC")) {
      try {
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
      } catch (final GeneralSecurityException e) {
        // se prueba con el siguiente algoritmo
      }
    }
    throw new IllegalStateException(
        "notification.auth.platform.public-key must be an RSA or EC public key in X.509 format");
  }

  private Claims parseClaims(final String rawToken) {
    try {
      return parser.parseSignedClaims(rawToken).getPayload();
    } catch (final JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException("invalid token", e);
    }
  }

  private AuthenticatedPrincipal toPrincipal(final Claims claims) {
    final String subject = claims.get(properties.subjectClaim(), String.class);
    final String tenant = claims.get(properties.tenantClaim(), String.class);
    if (!hasText(subject) || !hasText(tenant)) {
      throw new InvalidTokenException("missing required claims");
    }
    return new AuthenticatedPrincipal(subject, TenantId.of(tenant), resolveRole(claims));
  }

  /**
   * El claim de rol puede ser un texto o una lista. Cada valor se traduce con la tabla de roles y,
   * si hay varios validos, gana el de mayor privilegio.
   */
  private Role resolveRole(final Claims claims) {
    final Object claim = claims.get(properties.roleClaim());
    final Stream<?> values =
        claim instanceof Collection<?> collection ? collection.stream() : Stream.ofNullable(claim);
    return values
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .map(this::translate)
        .flatMap(Optional::stream)
        .min(Comparator.comparingInt(Role::ordinal))
        .orElseThrow(() -> new InvalidTokenException("unknown role"));
  }

  private Optional<Role> translate(final String external) {
    final String internal = properties.roleMapping().getOrDefault(external, external);
    try {
      return Optional.of(Role.of(internal));
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static boolean hasText(final String value) {
    return value != null && !value.isBlank();
  }
}
