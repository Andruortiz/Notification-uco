package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import reactor.core.publisher.Mono;

public interface EraseRecipientDevicesUseCase {

  Mono<Void> erase(AuthenticatedPrincipal principal, String subject);
}
