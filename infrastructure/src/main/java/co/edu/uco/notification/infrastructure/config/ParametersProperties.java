package co.edu.uco.notification.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.parameters")
public record ParametersProperties(
    String baseUrl,
    Long pollIntervalMs,
    Long timeoutMs,
    Long connectTimeoutMs,
    Long lastKnownLoadTimeoutMs) {

  private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;
  private static final long DEFAULT_TIMEOUT_MS = 5_000L;
  private static final long DEFAULT_CONNECT_TIMEOUT_MS = 2_000L;
  private static final long DEFAULT_LAST_KNOWN_LOAD_TIMEOUT_MS = 5_000L;

  public ParametersProperties {
    baseUrl = baseUrl == null ? "" : baseUrl.trim();
    if (pollIntervalMs == null) {
      pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    }
    if (timeoutMs == null) {
      timeoutMs = DEFAULT_TIMEOUT_MS;
    }
    if (connectTimeoutMs == null) {
      connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    }
    if (lastKnownLoadTimeoutMs == null) {
      lastKnownLoadTimeoutMs = DEFAULT_LAST_KNOWN_LOAD_TIMEOUT_MS;
    }
  }

  public boolean hasSource() {
    return !baseUrl.isEmpty();
  }

  public Duration timeout() {
    return Duration.ofMillis(timeoutMs);
  }

  public Duration lastKnownLoadTimeout() {
    return Duration.ofMillis(lastKnownLoadTimeoutMs);
  }
}
