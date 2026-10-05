package co.edu.uco.notification.infrastructure.config;

import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

public final class AuthJwtSecretGuard {

  public static final String DEVELOPMENT_SECRET =
      "notification-uco-dev-only-secret-change-me-0123456789abcdef";
  public static final String LOCAL_PROFILE = "local";
  public static final String SECRET_VARIABLE = "AUTH_JWT_HS256_SECRET";
  public static final int MINIMUM_SECRET_BYTES = 32;

  public AuthJwtSecretGuard(final AuthJwtProperties properties, final Environment environment) {
    final String secret = properties.hs256Secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "notification.auth.jwt.hs256-secret is required: set "
              + SECRET_VARIABLE
              + " or activate the '"
              + LOCAL_PROFILE
              + "' profile for development");
    }
    if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
      throw new IllegalStateException(
          SECRET_VARIABLE + " must be at least " + MINIMUM_SECRET_BYTES + " bytes long");
    }
    if (DEVELOPMENT_SECRET.equals(secret)
        && !environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE))) {
      throw new IllegalStateException(
          SECRET_VARIABLE
              + " matches the published development secret, which is only accepted with the '"
              + LOCAL_PROFILE
              + "' profile");
    }
  }
}
