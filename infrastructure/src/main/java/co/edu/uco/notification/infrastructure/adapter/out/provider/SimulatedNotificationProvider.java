package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.ProbeOutcome;
import co.edu.uco.notification.core.port.out.ProviderProbePort;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.infrastructure.config.SimulatedProviderProperties;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@EnableConfigurationProperties(SimulatedProviderProperties.class)
public class SimulatedNotificationProvider implements NotificationSenderPort, ProviderProbePort {

  private static final ProviderId PROVIDER_ID = ProviderId.of("simulated");
  private static final String DISABLED_REASON = "notification.provider.simulated.enabled=false";

  private final SimulatedProviderProperties properties;

  public SimulatedNotificationProvider(final SimulatedProviderProperties properties) {
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
  }

  @Override
  public Mono<ProviderResult> send(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    if (!properties.enabled()) {
      return Mono.error(new ProviderDisabledException(PROVIDER_ID, DISABLED_REASON));
    }
    return Mono.just(ProviderResult.of(properties.result()));
  }

  @Override
  public Mono<ProbeOutcome> probe() {
    if (!properties.enabled()) {
      return Mono.just(ProbeOutcome.failed(ProbeErrorCode.CREDENTIALS_MISSING));
    }
    return Mono.just(
        properties.result() == AttemptResult.ACCEPTED
            ? ProbeOutcome.ok()
            : ProbeOutcome.failed(ProbeErrorCode.SIMULATED_FAILURE));
  }

  @Override
  public ProviderId providerId() {
    return PROVIDER_ID;
  }

  @Override
  public Optional<String> disabledReason() {
    return properties.enabled() ? Optional.empty() : Optional.of(DISABLED_REASON);
  }

  @Override
  public boolean supportsAttachments() {
    return true;
  }
}
