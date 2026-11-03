package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ProviderHealthReport;
import reactor.core.publisher.Mono;

public interface GetProviderHealthUseCase {

  Mono<ProviderHealthReport> getHealth();
}
