package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.DispatchResultNotPersistedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public final class DispatchNotificationService implements DispatchNotificationUseCase {

  private static final System.Logger LOGGER =
      System.getLogger(DispatchNotificationService.class.getName());
  private static final long SAVE_RETRIES = 2;
  private static final Duration SAVE_RETRY_DELAY = Duration.ofMillis(100);

  private final NotificationRepository notificationRepository;
  private final ChannelCatalogPort channelCatalogPort;
  private final NotificationSenderRegistry notificationSenderRegistry;
  private final NotificationEventPublisherPort eventPublisherPort;
  private final RetryPolicy retryPolicy;

  public DispatchNotificationService(
      final NotificationRepository notificationRepository,
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.channelCatalogPort =
        Preconditions.requireNonNull(channelCatalogPort, "channelCatalogPort must not be null");
    this.notificationSenderRegistry =
        Preconditions.requireNonNull(
            notificationSenderRegistry, "notificationSenderRegistry must not be null");
    this.eventPublisherPort =
        Preconditions.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
    this.retryPolicy = Preconditions.requireNonNull(retryPolicy, "retryPolicy must not be null");
  }

  private record Outcome(AttemptResult result, ProviderId providerId) {}

  @Override
  public Mono<Void> dispatch(final NotificationId notificationId) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");

    return notificationRepository
        .reserveForDispatch(notificationId)
        .flatMap(reserved -> dispatchReserved(reserved).thenReturn(Boolean.TRUE))
        .defaultIfEmpty(Boolean.FALSE)
        .flatMap(reserved -> reserved ? Mono.<Void>empty() : handleNotReserved(notificationId));
  }

  private Mono<Void> handleNotReserved(final NotificationId notificationId) {
    return notificationRepository
        .findById(notificationId)
        .switchIfEmpty(Mono.error(new NotificationNotFoundException(notificationId)))
        .doOnNext(
            notification ->
                LOGGER.log(
                    System.Logger.Level.INFO,
                    "DISPATCH_REDELIVERY_IGNORED notificationId="
                        + notificationId.value()
                        + " status="
                        + notification.status()))
        .then();
  }

  private Mono<Void> dispatchReserved(final Notification notification) {
    notification.announceQueued();
    return Mono.defer(() -> resolveOutcome(notification))
        .onErrorResume(error -> releaseAndPropagate(notification, error))
        .flatMap(outcome -> persistAndPublish(notification, outcome));
  }

  private Mono<Outcome> resolveOutcome(final Notification notification) {
    return channelCatalogPort
        .findActiveRoute(notification.channelType(), notification.tenantId())
        .switchIfEmpty(Mono.error(new ChannelNotAvailableException(notification.channelType())))
        .flatMap(route -> sendThrough(notification, route));
  }

  private Mono<Outcome> sendThrough(final Notification notification, final ChannelRoute route) {
    final ProviderId providerId = route.preferredProvider();
    return Mono.fromCallable(() -> notificationSenderRegistry.resolve(providerId))
        .flatMap(sender -> attempt(sender, notification))
        .map(result -> new Outcome(result, providerId));
  }

  private static Mono<AttemptResult> attempt(
      final NotificationSenderPort sender, final Notification notification) {
    return notification.content().hasAttachments() && !sender.supportsAttachments()
        ? Mono.just(AttemptResult.PERMANENT_FAILURE)
        : sender.send(notification);
  }

  private Mono<Outcome> releaseAndPropagate(
      final Notification notification, final Throwable error) {
    return notificationRepository
        .releaseReservation(notification.notificationId())
        .onErrorResume(
            releaseFailure -> {
              LOGGER.log(
                  System.Logger.Level.ERROR,
                  "DISPATCH_RESERVATION_NOT_RELEASED notificationId="
                      + notification.notificationId().value()
                      + " errorCode="
                      + ErrorCode.DISPATCH_RESERVATION_NOT_RELEASED.format(),
                  releaseFailure);
              return Mono.empty();
            })
        .then(Mono.<Outcome>error(error));
  }

  private Mono<Void> persistAndPublish(final Notification notification, final Outcome outcome) {
    applyOutcome(notification, outcome.result(), outcome.providerId(), retryPolicy);
    final List<DomainEvent> events = notification.pullEvents();

    return Mono.defer(() -> notificationRepository.save(notification))
        .retryWhen(
            Retry.fixedDelay(SAVE_RETRIES, SAVE_RETRY_DELAY)
                .filter(error -> !(error instanceof NotificationVersionConflictException))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
        .onErrorMap(
            error -> !(error instanceof NotificationVersionConflictException),
            error -> new DispatchResultNotPersistedException(notification.notificationId(), error))
        .flatMap(saved -> publishEvents(notification, events));
  }

  private Mono<Void> publishEvents(
      final Notification notification, final List<DomainEvent> events) {
    return eventPublisherPort
        .publish(events)
        .onErrorResume(
            error -> {
              LOGGER.log(
                  System.Logger.Level.ERROR,
                  "DISPATCH_EVENTS_NOT_PUBLISHED notificationId="
                      + notification.notificationId().value()
                      + " errorCode="
                      + ErrorCode.DISPATCH_EVENTS_NOT_PUBLISHED.format(),
                  error);
              return Mono.empty();
            });
  }

  private static void applyOutcome(
      final Notification notification,
      final AttemptResult outcome,
      final ProviderId providerId,
      final RetryPolicy retryPolicy) {
    if (outcome == AttemptResult.ACCEPTED) {
      notification.markDelivered(AttemptOrigin.AUTOMATIC, providerId);
    } else if (outcome == AttemptResult.RECOVERABLE_FAILURE) {
      final int attemptNumber = notification.recoverableAttemptsInCurrentCycle() + 1;
      if (retryPolicy.shouldGiveUp(attemptNumber)) {
        notification.markRetriesExhausted(AttemptOrigin.AUTOMATIC, providerId);
      } else {
        notification.markRecoverable(AttemptOrigin.AUTOMATIC, providerId);
      }
    } else {
      notification.markFailed(AttemptOrigin.AUTOMATIC, providerId);
    }
  }
}
