package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import reactor.core.publisher.Mono;

public interface RegisterDeviceUseCase {

  record Registration(Device device, boolean created) {}

  Mono<Registration> register(
      AuthenticatedPrincipal principal, DevicePlatform platform, String token, String subject);
}
