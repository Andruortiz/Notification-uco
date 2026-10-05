package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.port.in.IssueSubscriptionTicketUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class SubscriptionTicketController {

  private final IssueSubscriptionTicketUseCase issueSubscriptionTicketUseCase;

  public SubscriptionTicketController(
      final IssueSubscriptionTicketUseCase issueSubscriptionTicketUseCase) {
    this.issueSubscriptionTicketUseCase =
        Preconditions.requireNonNull(
            issueSubscriptionTicketUseCase, "issueSubscriptionTicketUseCase must not be null");
  }

  @PostMapping("/notifications:subscribeTicket")
  public Mono<SubscriptionTicketResponse> issue(final AuthenticatedPrincipal principal) {
    return issueSubscriptionTicketUseCase.issue(principal).map(SubscriptionTicketResponse::from);
  }
}
