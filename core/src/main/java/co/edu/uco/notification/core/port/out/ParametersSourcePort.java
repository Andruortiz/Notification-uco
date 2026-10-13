package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import reactor.core.publisher.Mono;

public interface ParametersSourcePort {

  Mono<ConfigurationChange> fetchState();
}
