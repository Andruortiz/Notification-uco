package co.edu.uco.notification.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.auth.session")
public record AuthSessionProperties(String url, String path, Long timeoutMs) {

  private static final String DEFAULT_PATH = "/api/v1/session";
  private static final long DEFAULT_TIMEOUT_MS = 3_000L;

  public AuthSessionProperties {
    url = url == null ? "" : url.trim();
    path = path == null || path.isBlank() ? DEFAULT_PATH : path.trim();
    if (timeoutMs == null) {
      timeoutMs = DEFAULT_TIMEOUT_MS;
    }
  }

  public Duration timeout() {
    return Duration.ofMillis(timeoutMs);
  }
}
