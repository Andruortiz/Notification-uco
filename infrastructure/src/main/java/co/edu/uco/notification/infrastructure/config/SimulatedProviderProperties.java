package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.provider.simulated")
public record SimulatedProviderProperties(AttemptResult result, boolean enabled) {

  public SimulatedProviderProperties {
    result = result == null ? AttemptResult.ACCEPTED : result;
  }
}
