package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import reactor.core.publisher.Mono;

public interface ProviderProbePort {

  ProviderId providerId();

  Mono<ProbeOutcome> probe();
}
