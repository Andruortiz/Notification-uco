package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

public final class RequeuePendingNotificationsService
    implements RequeuePendingNotificationsUseCase {

  private final NotificationRepository notificationRepository;
  private final NotificationEventPublisherPort eventPublisherPort;
  private final RetryPolicy retryPolicy;

  public RequeuePendingNotificationsService(
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.eventPublisherPort =
        Preconditions.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
    this.retryPolicy = Preconditions.requireNonNull(retryPolicy, "retryPolicy must not be null");
  }

  @Override
  public Mono<Void> requeuePending() {
    return notificationRepository
        .findByStatus(NotificationStatus.RECOVERABLE)
        .filter(this::isDue)
        .flatMap(
            notification ->
                requeueAndPublish(notification)
                    .onErrorResume(
                        NotificationVersionConflictException.class, error -> Mono.empty()))
        .then();
  }

  private boolean isDue(final Notification notification) {
    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    final DeliveryAttempt lastAttempt = attempts.getLast();
    final int recoverableAttemptCount = recoverableAttemptCount(attempts);
    final Instant dueAt =
        lastAttempt.occurredOn().plus(retryPolicy.nextBackoff(recoverableAttemptCount));
    return !Instant.now().isBefore(dueAt);
  }

  private static int recoverableAttemptCount(final List<DeliveryAttempt> attempts) {
    return (int)
        attempts.stream()
            .filter(attempt -> attempt.result() == AttemptResult.RECOVERABLE_FAILURE)
            .count();
  }

  private Mono<Void> requeueAndPublish(final Notification notification) {
    notification.requeue();
    final List<DomainEvent> events = notification.pullEvents();

    return notificationRepository
        .save(notification)
        .flatMap(
            saved ->
                eventPublisherPort
                    .publish(events)
                    .then(eventPublisherPort.enqueueForDispatch(saved)))
        .then();
  }
}
