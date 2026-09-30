package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import reactor.core.publisher.Mono;

public interface TokenValidationPort {

  Mono<AuthenticatedPrincipal> validate(String rawToken);
}
