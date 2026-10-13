package co.edu.uco.notification.core.port.in;

import reactor.core.publisher.Mono;

public interface QueryConfigurationUseCase {

  Mono<ConfigurationDescription> describe();
}
