package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class RequeuePendingNotificationsService
    implements RequeuePendingNotificationsUseCase {

  private static final System.Logger LOGGER =
      System.getLogger(RequeuePendingNotificationsService.class.getName());

  private final NotificationRepository notificationRepository;
  private final NotificationEventPublisherPort eventPublisherPort;
  private final RetryPolicy retryPolicy;
  private final Duration pendingOrphanThreshold;
  private final Duration inProcessTimeout;
  private final int batchSize;
  private final NotificationMetricsPort metricsPort;

  public RequeuePendingNotificationsService(
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy,
      final Duration pendingOrphanThreshold,
      final Duration inProcessTimeout,
      final int batchSize,
      final NotificationMetricsPort metricsPort) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.eventPublisherPort =
        Preconditions.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
    this.retryPolicy = Preconditions.requireNonNull(retryPolicy, "retryPolicy must not be null");
    this.pendingOrphanThreshold =
        Preconditions.requireNonNull(
            pendingOrphanThreshold, "pendingOrphanThreshold must not be null");
    this.inProcessTimeout =
        Preconditions.requireNonNull(inProcessTimeout, "inProcessTimeout must not be null");
    Preconditions.requireTrue(batchSize > 0, "batchSize must be positive");
    this.batchSize = batchSize;
    this.metricsPort = Preconditions.requireNonNull(metricsPort, "metricsPort must not be null");
  }

  @Override
  public Mono<Void> requeuePending() {
    return Mono.when(
        recoverExpiredRecoverable(), recoverOrphanedPending(), releaseStuckInProcess());
  }

  private Mono<Void> recoverExpiredRecoverable() {
    return Flux.defer(() -> notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .filter(this::isDue)
        .take(batchSize)
        .flatMap(
            notification ->
                requeueAndPublish(notification)
                    .onErrorResume(
                        NotificationVersionConflictException.class, error -> Mono.empty())
                    .onErrorResume(
                        error ->
                            logFailure(
                                "REQUEUE_FAILED", ErrorCode.REQUEUE_FAILED, notification, error)))
        .onErrorResume(error -> logPassFailure("REQUEUE_RECOVERABLE_PASS_FAILED", error))
        .then();
  }

  private Mono<Void> recoverOrphanedPending() {
    return Flux.defer(
            () ->
                notificationRepository.claimForRequeue(
                    Instant.now().minus(pendingOrphanThreshold), batchSize))
        .flatMap(
            notification ->
                eventPublisherPort
                    .enqueueForDispatch(notification)
                    .onErrorResume(
                        error ->
                            logFailure(
                                "ENQUEUE_FAILED", ErrorCode.ENQUEUE_FAILED, notification, error)))
        .onErrorResume(error -> logPassFailure("REQUEUE_ORPHAN_PASS_FAILED", error))
        .then();
  }

  private Mono<Void> releaseStuckInProcess() {
    return Flux.defer(
            () ->
                notificationRepository.claimStuckInProcess(
                    Instant.now().minus(inProcessTimeout), batchSize))
        .doOnNext(
            notification ->
                LOGGER.log(
                    System.Logger.Level.WARNING,
                    "DISPATCH_STUCK_IN_PROCESS_RELEASED notificationId="
                        + notification.notificationId().value()
                        + " tenantId="
                        + notification.tenantId().value()
                        + " errorCode="
                        + ErrorCode.DISPATCH_STUCK_IN_PROCESS_RELEASED.format()))
        .doOnNext(
            notification -> metricsPort.errorRecorded(ErrorCode.DISPATCH_STUCK_IN_PROCESS_RELEASED))
        .onErrorResume(error -> logPassFailure("REQUEUE_STUCK_PASS_FAILED", error))
        .then();
  }

  private <T> Mono<T> logFailure(
      final String marker,
      final ErrorCode code,
      final Notification notification,
      final Throwable error) {
    LOGGER.log(
        System.Logger.Level.ERROR,
        marker
            + " notificationId="
            + notification.notificationId().value()
            + " tenantId="
            + notification.tenantId().value()
            + " errorCode="
            + code.format(),
        error);
    metricsPort.errorRecorded(code);
    return Mono.empty();
  }

  private <T> Mono<T> logPassFailure(final String code, final Throwable error) {
    LOGGER.log(
        System.Logger.Level.ERROR,
        code + " errorCode=" + ErrorCode.REQUEUE_PASS_FAILED.format(),
        error);
    metricsPort.errorRecorded(ErrorCode.REQUEUE_PASS_FAILED);
    return Mono.empty();
  }

  private boolean isDue(final Notification notification) {
    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    final int recoverableAttemptCount = notification.recoverableAttemptsInCurrentCycle();
    if (attempts.isEmpty() || recoverableAttemptCount == 0) {
      return true;
    }
    final DeliveryAttempt lastAttempt = attempts.getLast();
    final Instant dueAt =
        lastAttempt.occurredOn().plus(retryPolicy.nextBackoff(recoverableAttemptCount));
    return !Instant.now().isBefore(dueAt);
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
