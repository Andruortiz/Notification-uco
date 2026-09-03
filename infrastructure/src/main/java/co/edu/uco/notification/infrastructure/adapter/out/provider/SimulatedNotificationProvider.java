package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.AttemptResult;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@EnableConfigurationProperties(SimulatedProviderProperties.class)
public class SimulatedNotificationProvider implements NotificationSenderPort {

  private final SimulatedProviderProperties properties;

  public SimulatedNotificationProvider(final SimulatedProviderProperties properties) {
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
  }

  @Override
  public Mono<AttemptResult> send(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    return Mono.just(properties.result());
  }
}
