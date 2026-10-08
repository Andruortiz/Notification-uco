package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.port.in.ReceivePublishedConfigurationUseCase;
import co.edu.uco.notification.core.port.in.SynchronizeConfigurationUseCase;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class SynchronizeConfigurationService implements SynchronizeConfigurationUseCase {

  private final ParametersSourcePort source;
  private final ReceivePublishedConfigurationUseCase receiveUseCase;

  public SynchronizeConfigurationService(
      final ParametersSourcePort source,
      final ReceivePublishedConfigurationUseCase receiveUseCase) {
    this.source = Preconditions.requireNonNull(source, "source must not be null");
    this.receiveUseCase =
        Preconditions.requireNonNull(receiveUseCase, "receiveUseCase must not be null");
  }

  @Override
  public Mono<ConfigurationChangeOutcome> synchronize() {
    return Mono.defer(source::fetchState).flatMap(receiveUseCase::receive);
  }
}
