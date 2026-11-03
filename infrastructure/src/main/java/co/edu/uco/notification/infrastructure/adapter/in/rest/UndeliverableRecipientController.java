package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.port.in.FindUndeliverableMarkQuery;
import co.edu.uco.notification.core.port.in.LiftUndeliverableMarkUseCase;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/recipients")
public class UndeliverableRecipientController {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(UndeliverableRecipientController.class);

  private final FindUndeliverableMarkQuery findQuery;
  private final LiftUndeliverableMarkUseCase liftUseCase;

  public UndeliverableRecipientController(
      final FindUndeliverableMarkQuery findQuery, final LiftUndeliverableMarkUseCase liftUseCase) {
    this.findQuery = Preconditions.requireNonNull(findQuery, "findQuery must not be null");
    this.liftUseCase = Preconditions.requireNonNull(liftUseCase, "liftUseCase must not be null");
  }

  @PostMapping("/undeliverable-lookups")
  public Mono<UndeliverableRecipientResponse> lookup(
      final AuthenticatedPrincipal principal,
      @RequestBody final UndeliverableRecipientRequest request) {
    final ChannelType channel = channelOf(request);
    return findQuery
        .find(
            principal.tenantId(),
            channel,
            Preconditions.requireNonBlank(request.address(), "address must not be blank"))
        .map(view -> UndeliverableRecipientResponse.from(view, principal.role()));
  }

  @PostMapping("/undeliverable-releases")
  public Mono<ResponseEntity<Void>> release(
      final AuthenticatedPrincipal principal,
      @RequestBody final UndeliverableRecipientRequest request) {
    final ChannelType channel = channelOf(request);
    return liftUseCase
        .lift(
            principal.tenantId(),
            channel,
            Preconditions.requireNonBlank(request.address(), "address must not be blank"))
        .doOnSuccess(ignored -> logReleased(principal, channel))
        .thenReturn(ResponseEntity.noContent().build());
  }

  private static ChannelType channelOf(final UndeliverableRecipientRequest request) {
    return ChannelType.of(request.channel());
  }

  private static void logReleased(
      final AuthenticatedPrincipal principal, final ChannelType channel) {
    try (LogContext ignored =
        LogContext.open(null, LogSanitizer.safe(principal.tenantId().value()), null)) {
      LOGGER.info(LogFields.fields("channel", channel.value()), "Undeliverable mark released");
    }
  }
}
