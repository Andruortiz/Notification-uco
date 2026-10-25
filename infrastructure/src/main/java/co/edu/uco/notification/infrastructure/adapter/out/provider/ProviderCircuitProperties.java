package co.edu.uco.notification.infrastructure.adapter.out.provider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.provider.circuit-breaker")
public record ProviderCircuitProperties(
    Float failureRateThreshold,
    Integer minimumCalls,
    Integer slidingWindowSize,
    Duration openWait,
    Integer halfOpenCalls) {

  public ProviderCircuitProperties {
    failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
    minimumCalls = minimumCalls == null ? 5 : minimumCalls;
    slidingWindowSize = slidingWindowSize == null ? 10 : slidingWindowSize;
    openWait = openWait == null ? Duration.ofSeconds(30) : openWait;
    halfOpenCalls = halfOpenCalls == null ? 2 : halfOpenCalls;
  }
}
