package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.domain.valueobject.ProviderHealthSettings;
import co.edu.uco.notification.core.port.in.GetProviderHealthUseCase;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort;
import co.edu.uco.notification.core.usecase.GetProviderHealthService;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProviderHealthProperties.class)
public class ProviderHealthConfig {

  private static final String DASHBOARD_PATH = "/d/notification-service";

  @Bean
  GetProviderHealthUseCase getProviderHealthUseCase(
      final ProviderMetricsQueryPort metricsQueryPort,
      final ProviderHealthProperties properties,
      final Clock clock) {
    return new GetProviderHealthService(
        metricsQueryPort,
        new ProviderHealthSettings(
            properties.window(),
            properties.lookback(),
            properties.minSamples(),
            properties.healthyThreshold(),
            properties.downThreshold(),
            properties.recoverableThreshold(),
            dashboardUrl(properties.grafanaUrl())),
        clock);
  }

  private static String dashboardUrl(final String grafanaUrl) {
    if (grafanaUrl == null || grafanaUrl.isBlank()) {
      return null;
    }
    final String base = grafanaUrl.strip();
    return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + DASHBOARD_PATH;
  }
}
