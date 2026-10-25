package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import reactor.core.publisher.Flux;

public interface ListDevicesUseCase {

  Flux<Device> list(AuthenticatedPrincipal principal, String subject);
}
