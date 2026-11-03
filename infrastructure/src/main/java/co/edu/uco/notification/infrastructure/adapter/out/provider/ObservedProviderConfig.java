package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProviderCircuitProperties.class)
public class ObservedProviderConfig {

  @Bean
  NotificationSenderRegistry notificationSenderRegistry(
      final List<NotificationSenderPort> notificationSenderPorts,
      final ObservationRegistry observationRegistry,
      final ProviderCircuitProperties circuitProperties,
      @Qualifier("providerCircuitMillis") final ObjectProvider<LongSupplier> circuitMillis) {
    final LongSupplier millis = circuitMillis.getIfAvailable(() -> System::currentTimeMillis);
    return new NotificationSenderRegistry(
        notificationSenderPorts.stream()
            .<NotificationSenderPort>map(
                sender ->
                    new ObservedNotificationSender(
                        new CircuitBreakerNotificationSender(sender, circuitProperties, millis),
                        observationRegistry))
            .toList());
  }
}
