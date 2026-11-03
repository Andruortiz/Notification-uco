package co.edu.uco.notification.infrastructure.adapter.out.provider;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.provider.circuit-breaker")
public record ProviderCircuitProperties(
    Float failureRateThreshold,
    Integer minimumCalls,
    Integer slidingWindowSize,
    Duration openWait,
    Integer halfOpenCalls) {

  public ProviderCircuitProperties {
    failureRateThreshold = Objects.requireNonNullElse(failureRateThreshold, Float.valueOf(50f));
    minimumCalls = Objects.requireNonNullElse(minimumCalls, Integer.valueOf(5));
    slidingWindowSize = Objects.requireNonNullElse(slidingWindowSize, Integer.valueOf(10));
    openWait = Objects.requireNonNullElse(openWait, Duration.ofSeconds(30));
    halfOpenCalls = Objects.requireNonNullElse(halfOpenCalls, Integer.valueOf(2));
  }
}
