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
  }
}
