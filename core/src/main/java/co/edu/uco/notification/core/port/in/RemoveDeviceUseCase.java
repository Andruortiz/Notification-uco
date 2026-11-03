package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import reactor.core.publisher.Mono;

public interface RemoveDeviceUseCase {

  Mono<Void> remove(AuthenticatedPrincipal principal, String deviceId);
}
