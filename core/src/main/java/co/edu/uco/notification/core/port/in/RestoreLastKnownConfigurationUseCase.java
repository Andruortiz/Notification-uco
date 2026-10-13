package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import reactor.core.publisher.Mono;

public interface RestoreLastKnownConfigurationUseCase {

  Mono<ConfigurationSnapshot> restore();
}
