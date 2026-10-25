package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.port.in.TestProviderConnectionUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.ProviderProbePort;
import co.edu.uco.notification.core.port.out.ProviderProbeRegistry;
import co.edu.uco.notification.core.usecase.TestProviderConnectionService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProviderProbeConfig {

  @Bean
  ProviderProbeRegistry providerProbeRegistry(final List<ProviderProbePort> probes) {
    return new ProviderProbeRegistry(probes);
  }

  @Bean
  TestProviderConnectionUseCase testProviderConnectionUseCase(
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry,
      final ProviderProbeRegistry providerProbeRegistry,
      final Clock clock,
      @Value("${notification.provider.probe.timeout:5s}") final Duration timeout) {
    return new TestProviderConnectionService(
        channelCatalogPort, notificationSenderRegistry, providerProbeRegistry, clock, timeout);
  }
}
