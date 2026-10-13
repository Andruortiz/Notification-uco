package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import java.time.Instant;
import reactor.core.publisher.Mono;

public interface SubscriptionTicketPort {

  Mono<Void> save(String fingerprint, AuthenticatedPrincipal principal, Instant expiresAt);

  Mono<AuthenticatedPrincipal> consume(String fingerprint);
}
