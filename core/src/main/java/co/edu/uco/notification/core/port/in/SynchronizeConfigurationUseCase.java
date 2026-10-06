package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import reactor.core.publisher.Mono;

public interface SynchronizeConfigurationUseCase {

  Mono<ConfigurationChangeOutcome> synchronize();
}
