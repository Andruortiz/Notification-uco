package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.policy.StatusTransitionPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationStateNotAllowedException;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.RetryNotificationCommand;
import co.edu.uco.notification.core.port.in.RetryNotificationUseCase;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class RetryNotificationService implements RetryNotificationUseCase {

  private final NotificationRepository notificationRepository;
  private final NotificationEventPublisherPort eventPublisherPort;

  public RetryNotificationService(
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.eventPublisherPort =
        Preconditions.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
  }

  @Override
  public Mono<NotificationStatusView> retry(final RetryNotificationCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");

    return notificationRepository
        .findById(command.notificationId())
        .filter(notification -> notification.tenantId().equals(command.tenantId()))
        .switchIfEmpty(Mono.error(new NotificationNotFoundException(command.notificationId())))
        .flatMap(this::requeueAndEnqueue);
  }

  private Mono<NotificationStatusView> requeueAndEnqueue(final Notification notification) {
    if (!StatusTransitionPolicy.canTransition(notification.status(), NotificationStatus.PENDING)) {
      return Mono.error(new NotificationStateNotAllowedException(notification.status()));
    }
    notification.requeue();
    return notificationRepository
        .save(notification)
        .flatMap(
            saved ->
                eventPublisherPort
                    .publish(notification.pullEvents())
                    .then(eventPublisherPort.enqueueForDispatch(saved, AttemptOrigin.MANUAL))
                    .thenReturn(NotificationStatusView.from(saved)));
  }
}
