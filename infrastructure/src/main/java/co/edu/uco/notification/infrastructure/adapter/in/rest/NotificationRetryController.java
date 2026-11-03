package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.RetryNotificationCommand;
import co.edu.uco.notification.core.port.in.RetryNotificationUseCase;
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
@RequestMapping("/notification-retries")
public class NotificationRetryController {

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationRetryController.class);

  private final RetryNotificationUseCase retryNotificationUseCase;

  public NotificationRetryController(final RetryNotificationUseCase retryNotificationUseCase) {
    this.retryNotificationUseCase =
        Preconditions.requireNonNull(
            retryNotificationUseCase, "retryNotificationUseCase must not be null");
  }

  @PostMapping
  public Mono<ResponseEntity<NotificationStatusResponse>> retry(
      final AuthenticatedPrincipal principal, @RequestBody final RetryNotificationRequest request) {
    final NotificationId notificationId = NotificationId.of(request.notificationId());
    return retryNotificationUseCase
        .retry(new RetryNotificationCommand(principal.tenantId(), notificationId))
        .doOnSuccess(view -> logRetried(principal, notificationId, view))
        .map(NotificationStatusResponse::from)
        .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response));
  }

  private static void logRetried(
      final AuthenticatedPrincipal principal,
      final NotificationId notificationId,
      final NotificationStatusView view) {
    try (LogContext ignored =
        LogContext.open(
            view.correlationId(),
            LogSanitizer.safe(principal.tenantId().value()),
            notificationId.value())) {
      LOGGER.info(LogFields.fields("status", view.status().name()), "Manual retry accepted");
    }
  }
}
