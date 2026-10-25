package co.edu.uco.notification.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "provider-health")
public record ProviderHealthProperties(
    String prometheusUrl,
    String grafanaUrl,
    Duration window,
    Duration lookback,
    Duration timeout,
    Long minSamples,
    Double healthyThreshold,
    Double downThreshold,
    Double recoverableThreshold) {

  public ProviderHealthProperties {
    window = window == null ? Duration.ofMinutes(5) : window;
    lookback = lookback == null ? Duration.ofMinutes(15) : lookback;
    timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
    minSamples = minSamples == null ? 5L : minSamples;
    healthyThreshold = healthyThreshold == null ? 0.95 : healthyThreshold;
    downThreshold = downThreshold == null ? 0.5 : downThreshold;
    recoverableThreshold = recoverableThreshold == null ? 0.5 : recoverableThreshold;
    require(window.compareTo(Duration.ZERO) > 0, "window must be positive");
    require(lookback.compareTo(Duration.ofSeconds(60)) >= 0, "lookback must be at least 60s");
    require(timeout.compareTo(Duration.ZERO) > 0, "timeout must be positive");
    require(minSamples >= 0, "minSamples must not be negative");
    require(inUnit(healthyThreshold), "healthyThreshold must be between 0 and 1");
    require(inUnit(downThreshold), "downThreshold must be between 0 and 1");
    require(inUnit(recoverableThreshold), "recoverableThreshold must be between 0 and 1");
  }

  private static boolean inUnit(final double value) {
    return value >= 0 && value <= 1;
  }

  private static void require(final boolean condition, final String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
