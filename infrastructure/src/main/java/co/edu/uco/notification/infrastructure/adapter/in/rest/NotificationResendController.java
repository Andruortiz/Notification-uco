package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.ResendNotificationCommand;
import co.edu.uco.notification.core.port.in.ResendNotificationUseCase;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/notification-resends")
public class NotificationResendController {

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationResendController.class);

  private final ResendNotificationUseCase resendNotificationUseCase;

  public NotificationResendController(final ResendNotificationUseCase resendNotificationUseCase) {
    this.resendNotificationUseCase =
        Preconditions.requireNonNull(
            resendNotificationUseCase, "resendNotificationUseCase must not be null");
  }

  @PostMapping
  public Mono<ResponseEntity<NotificationStatusResponse>> resend(
      final AuthenticatedPrincipal principal,
      @RequestBody final ResendNotificationRequest request) {
    final NotificationId originalId = NotificationId.of(request.notificationId());
    return Mono.deferContextual(
            context ->
                resendNotificationUseCase.resend(
                    new ResendNotificationCommand(
                        principal.tenantId(),
                        originalId,
                        ChannelType.of(request.channelType()),
                        Recipient.of(request.recipient()),
                        CorrelationContext.from(context))))
        .doOnSuccess(view -> logResent(principal, originalId, view))
        .map(NotificationStatusResponse::from)
        .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response));
  }

  private static void logResent(
      final AuthenticatedPrincipal principal,
      final NotificationId originalId,
      final NotificationStatusView view) {
    try (LogContext ignored =
        LogContext.open(
            view.correlationId(),
            LogSanitizer.safe(principal.tenantId().value()),
            view.notificationId().value())) {
      LOGGER.info(
          LogFields.fields(
              "originalNotificationId",
              originalId.value(),
              "channelType",
              view.channelType().value(),
              "status",
              view.status().name()),
          "Resend accepted");
    }
  }
}
