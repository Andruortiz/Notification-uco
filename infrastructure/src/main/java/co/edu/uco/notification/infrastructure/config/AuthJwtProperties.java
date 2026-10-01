package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.auth.jwt")
public record AuthJwtProperties(String hs256Secret, Long ttlMinutes) {

  private static final Long DEFAULT_TTL_MINUTES = 720L;

  public AuthJwtProperties {
    ttlMinutes = ttlMinutes == null ? DEFAULT_TTL_MINUTES : ttlMinutes;
  }
}
