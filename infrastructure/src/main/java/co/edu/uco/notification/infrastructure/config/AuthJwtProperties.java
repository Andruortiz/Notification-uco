package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.auth.jwt")
public record AuthJwtProperties(String hs256Secret, Long ttlMinutes) {

  public static final long MINIMUM_TTL_MINUTES = 1L;
  public static final long MAXIMUM_TTL_MINUTES = 1440L;

  private static final Long DEFAULT_TTL_MINUTES = 720L;

  public AuthJwtProperties {
    ttlMinutes = ttlMinutes == null ? DEFAULT_TTL_MINUTES : ttlMinutes;
    if (ttlMinutes < MINIMUM_TTL_MINUTES || ttlMinutes > MAXIMUM_TTL_MINUTES) {
      throw new IllegalArgumentException(
          "notification.auth.jwt.ttl-minutes must be between "
              + MINIMUM_TTL_MINUTES
              + " and "
              + MAXIMUM_TTL_MINUTES);
    }
  }
}
