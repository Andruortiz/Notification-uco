package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.SubscriptionTicketFingerprint;
import co.edu.uco.notification.core.port.in.IssueSubscriptionTicketUseCase;
import co.edu.uco.notification.core.port.in.IssuedSubscriptionTicket;
import co.edu.uco.notification.core.port.out.SubscriptionTicketPort;
import co.edu.uco.notification.utils.Preconditions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import reactor.core.publisher.Mono;

public final class IssueSubscriptionTicketService implements IssueSubscriptionTicketUseCase {

  private static final int TICKET_BYTES = 32;

  private final SubscriptionTicketPort subscriptionTicketPort;
  private final Clock clock;
  private final Duration ttl;
  private final SecureRandom random = new SecureRandom();

  public IssueSubscriptionTicketService(
      final SubscriptionTicketPort subscriptionTicketPort, final Clock clock, final Duration ttl) {
    this.subscriptionTicketPort =
        Preconditions.requireNonNull(
            subscriptionTicketPort, "subscriptionTicketPort must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
    this.ttl = Preconditions.requireNonNull(ttl, "ttl must not be null");
  }

  @Override
  public Mono<IssuedSubscriptionTicket> issue(final AuthenticatedPrincipal principal) {
    Preconditions.requireNonNull(principal, "principal must not be null");
    final byte[] bytes = new byte[TICKET_BYTES];
    random.nextBytes(bytes);
    final String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return subscriptionTicketPort
        .save(SubscriptionTicketFingerprint.of(ticket), principal, clock.instant().plus(ttl))
        .thenReturn(new IssuedSubscriptionTicket(ticket, ttl.toSeconds()));
  }
}
