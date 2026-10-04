package co.edu.uco.notification.infrastructure.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion del modo {@code platform}: validacion de tokens emitidos por la plataforma central
 * de seguridad. Los nombres de los claims y la tabla de roles son configurables porque el contrato
 * con esa plataforma todavia no esta fijado.
 */
@ConfigurationProperties(prefix = "notification.auth.platform")
public record AuthPlatformProperties(
    String publicKey,
    String issuer,
    String audience,
    String subjectClaim,
    String tenantClaim,
    String roleClaim,
    Map<String, String> roleMapping,
    Long clockSkewSeconds) {

  private static final String DEFAULT_SUBJECT_CLAIM = "sub";
  private static final String DEFAULT_TENANT_CLAIM = "tenantId";
  private static final String DEFAULT_ROLE_CLAIM = "role";
  private static final Long DEFAULT_CLOCK_SKEW_SECONDS = 30L;

  public AuthPlatformProperties {
    subjectClaim = valueOrDefault(subjectClaim, DEFAULT_SUBJECT_CLAIM);
    tenantClaim = valueOrDefault(tenantClaim, DEFAULT_TENANT_CLAIM);
    roleClaim = valueOrDefault(roleClaim, DEFAULT_ROLE_CLAIM);
    roleMapping = roleMapping == null ? Map.of() : Map.copyOf(roleMapping);
    clockSkewSeconds = clockSkewSeconds == null ? DEFAULT_CLOCK_SKEW_SECONDS : clockSkewSeconds;
  }

  private static String valueOrDefault(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
