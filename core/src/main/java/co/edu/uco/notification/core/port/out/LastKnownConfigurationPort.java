package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import reactor.core.publisher.Mono;

public interface LastKnownConfigurationPort {

  Mono<ConfigurationSnapshot> load();

  Mono<Boolean> saveIfNewer(ConfigurationSnapshot snapshot);
}
