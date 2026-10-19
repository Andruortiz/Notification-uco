package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.SessionCredential;
import reactor.core.publisher.Mono;

public interface SessionIdentityPort {

  Mono<AuthenticatedPrincipal> resolve(SessionCredential credential);
}
