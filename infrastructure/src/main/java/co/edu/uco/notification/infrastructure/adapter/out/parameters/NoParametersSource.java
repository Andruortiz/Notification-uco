package co.edu.uco.notification.infrastructure.adapter.out.parameters;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import reactor.core.publisher.Mono;

public class NoParametersSource implements ParametersSourcePort {

  @Override
  public Mono<ConfigurationChange> fetchState() {
    return Mono.empty();
  }
}
