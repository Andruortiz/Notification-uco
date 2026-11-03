package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttemptRecord;
import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.FailureCause;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.AllProvidersCircuitOpenException;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.DispatchResultNotPersistedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.exception.ProviderCircuitOpenException;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.exception.ProviderNotAvailableException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.DeviceLifecyclePort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public final class DispatchNotificationService implements DispatchNotificationUseCase {

  private static final System.Logger LOGGER =
      System.getLogger(DispatchNotificationService.class.getName());
  private static final String PUSH_CHANNEL = "PUSH";
  private static final long SAVE_RETRIES = 2;
  private static final Duration SAVE_RETRY_DELAY = Duration.ofMillis(100);

  private final NotificationRepository notificationRepository;
  private final ChannelCatalogPort channelCatalogPort;
  private final NotificationSenderRegistry notificationSenderRegistry;
  private final NotificationEventPublisherPort eventPublisherPort;
  private final RetryPolicy retryPolicy;
  private final NotificationMetricsPort metricsPort;
  private final DeviceLifecyclePort deviceLifecyclePort;

  public DispatchNotificationService(
      final NotificationRepository notificationRepository,
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy,
      final NotificationMetricsPort metricsPort) {
    this(
        notificationRepository,
        channelCatalogPort,
        notificationSenderRegistry,
        eventPublisherPort,
        retryPolicy,
        metricsPort,
        DeviceLifecyclePort.noop());
  }

  public DispatchNotificationService(
      final NotificationRepository notificationRepository,
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy,
      final NotificationMetricsPort metricsPort,
      final DeviceLifecyclePort deviceLifecyclePort) {
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
    this.metricsPort = Preconditions.requireNonNull(metricsPort, "metricsPort must not be null");
    this.deviceLifecyclePort =
        Preconditions.requireNonNull(deviceLifecyclePort, "deviceLifecyclePort must not be null");
  }

  private record Outcome(ProviderResult last, List<ProviderResult> failedOver) {}

  @Override
  public Mono<Void> dispatch(final NotificationId notificationId, final AttemptOrigin origin) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(origin, "origin must not be null");

    return notificationRepository
        .reserveForDispatch(notificationId)
        .flatMap(reserved -> dispatchReserved(reserved, origin).thenReturn(Boolean.TRUE))
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

  private Mono<Void> dispatchReserved(final Notification notification, final AttemptOrigin origin) {
    notification.announceQueued();
    return Mono.defer(() -> resolveOutcome(notification))
        .onErrorResume(error -> releaseAndPropagate(notification, error))
        .flatMap(outcome -> persistAndPublish(notification, outcome, origin));
  }

  private Mono<Outcome> resolveOutcome(final Notification notification) {
    return channelCatalogPort
        .findActiveRoute(notification.channelType(), notification.tenantId())
        .switchIfEmpty(Mono.error(new ChannelNotAvailableException(notification.channelType())))
        .flatMap(route -> sendThrough(notification, route));
  }

  private Mono<Outcome> sendThrough(final Notification notification, final ChannelRoute route) {
    return tryProviders(notification, route.providers(), 0, List.of(), new Skips());
  }

  private Mono<Outcome> tryProviders(
      final Notification notification,
      final List<ProviderId> providers,
      final int index,
      final List<ProviderResult> recoverables,
      final Skips skips) {
    if (index >= providers.size()) {
      return recoverables.isEmpty()
          ? Mono.error(skips.terminalError(providers.getFirst(), notification, metricsPort))
          : Mono.just(
              new Outcome(
                  recoverables.getLast(), recoverables.subList(0, recoverables.size() - 1)));
    }
    final ProviderId providerId = providers.get(index);
    final Optional<NotificationSenderPort> sender = notificationSenderRegistry.find(providerId);
    if (sender.isEmpty()) {
      return tryProviders(notification, providers, index + 1, recoverables, skips);
    }
    final Optional<String> disabledReason = sender.get().disabledReason();
    if (disabledReason.isPresent()) {
      skips.disabled(providerId, disabledReason.get());
      return tryProviders(notification, providers, index + 1, recoverables, skips);
    }
    final long startedAt = System.nanoTime();
    return attempt(sender.get(), notification, providerId)
        .map(Optional::of)
        .onErrorResume(
            ProviderCircuitOpenException.class,
            error -> {
              skips.circuitOpen = true;
              return Mono.just(Optional.empty());
            })
        .onErrorResume(
            ProviderDisabledException.class,
            error -> {
              skips.disabled(providerId, error.getMessage());
              return Mono.just(Optional.empty());
            })
        .onErrorResume(
            DispatchNotificationService::isTransportError,
            error -> {
              LOGGER.log(
                  System.Logger.Level.WARNING,
                  "PROVIDER_TRANSPORT_ERROR providerId="
                      + providerId.value()
                      + " errorType="
                      + error.getClass().getSimpleName());
              return Mono.just(
                  Optional.of(
                      new ProviderResult(
                          AttemptResult.RECOVERABLE_FAILURE,
                          providerId,
                          Long.valueOf(Duration.ofNanos(System.nanoTime() - startedAt).toMillis()),
                          null,
                          FailureCause.TRANSPORT_ERROR)));
            })
        .flatMap(
            maybe ->
                maybe.isEmpty()
                    ? tryProviders(notification, providers, index + 1, recoverables, skips)
                    : afterAttempt(
                        notification, providers, index, recoverables, skips, maybe.get()));
  }

  private Mono<Outcome> afterAttempt(
      final Notification notification,
      final List<ProviderId> providers,
      final int index,
      final List<ProviderResult> recoverables,
      final Skips skips,
      final ProviderResult raw) {
    final ProviderId providerId = providers.get(index);
    final ProviderResult result =
        new ProviderResult(
            raw.result(),
            providerId,
            raw.latencyMillis(),
            raw.messageId(),
            raw.cause() == null ? defaultCause(raw.result()) : raw.cause());
    metricsPort.dispatchAttempted(notification.channelType(), providerId, result.result());
    if (result.result() != AttemptResult.RECOVERABLE_FAILURE) {
      return Mono.just(new Outcome(result, recoverables));
    }
    final List<ProviderResult> accumulated = new ArrayList<>(recoverables);
    accumulated.add(result);
    return tryProviders(notification, providers, index + 1, accumulated, skips);
  }

  private static final class Skips {
    private boolean circuitOpen;
    private ProviderId disabledProvider;
    private String disabledReason;

    private void disabled(final ProviderId providerId, final String reason) {
      if (disabledProvider == null) {
        disabledProvider = providerId;
        disabledReason = reason;
      }
    }

    private RuntimeException terminalError(
        final ProviderId preferred,
        final Notification notification,
        final NotificationMetricsPort metricsPort) {
      if (circuitOpen) {
        return new AllProvidersCircuitOpenException();
      }
      if (disabledProvider != null) {
        metricsPort.dispatchAttempted(
            notification.channelType(), disabledProvider, AttemptResult.PERMANENT_FAILURE);
        return new ProviderDisabledException(disabledProvider, disabledReason);
      }
      return new ProviderNotAvailableException(preferred);
    }
  }

  private Mono<ProviderResult> attempt(
      final NotificationSenderPort sender,
      final Notification notification,
      final ProviderId providerId) {
    if (notification.content().hasAttachments() && !sender.supportsAttachments()) {
      return Mono.just(
          new ProviderResult(
              AttemptResult.PERMANENT_FAILURE,
              providerId,
              null,
              null,
              FailureCause.ATTACHMENTS_UNSUPPORTED));
    }
    return Mono.defer(
        () -> {
          final long startedAt = System.nanoTime();
          return sender
              .send(notification)
              .map(
                  result -> {
                    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
                    metricsPort.providerCalled(providerId, result.result(), elapsed);
                    return new ProviderResult(
                        result.result(),
                        result.providerId(),
                        result.latencyMillis() == null
                            ? Long.valueOf(elapsed.toMillis())
                            : result.latencyMillis(),
                        result.messageId(),
                        result.cause());
                  });
        });
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
              metricsPort.errorRecorded(
                  ErrorCode.DISPATCH_RESERVATION_NOT_RELEASED, notification.channelType(), null);
              return Mono.empty();
            })
        .then(Mono.<Outcome>error(error));
  }

  private Mono<Void> persistAndPublish(
      final Notification notification, final Outcome outcome, final AttemptOrigin origin) {
    applyOutcome(notification, outcome, retryPolicy, origin);
    final List<DomainEvent> events = notification.pullEvents();

    return Mono.defer(() -> notificationRepository.save(notification))
        .retryWhen(
            Retry.fixedDelay(SAVE_RETRIES, SAVE_RETRY_DELAY)
                .filter(error -> !(error instanceof NotificationVersionConflictException))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
        .onErrorMap(
            error -> !(error instanceof NotificationVersionConflictException),
            error -> new DispatchResultNotPersistedException(notification.notificationId(), error))
        .doOnSuccess(saved -> logAttempts(notification, outcome.failedOver().size() + 1))
        .flatMap(saved -> publishEvents(notification, outcome.last().providerId(), events))
        .then(Mono.defer(() -> maintainDevice(notification, outcome.last().result())));
  }

  private Mono<Void> maintainDevice(final Notification notification, final AttemptResult result) {
    if (!PUSH_CHANNEL.equals(notification.channelType().value())) {
      return Mono.empty();
    }
    final Mono<Void> action =
        switch (result) {
          case INVALID_RECIPIENT ->
              deviceLifecyclePort.invalidate(
                  notification.tenantId(), notification.recipient().address());
          case ACCEPTED ->
              deviceLifecyclePort.touch(
                  notification.tenantId(), notification.recipient().address());
          case RECOVERABLE_FAILURE, PERMANENT_FAILURE -> Mono.empty();
        };
    return action.onErrorResume(
        error -> {
          LOGGER.log(
              System.Logger.Level.WARNING,
              "DEVICE_MAINTENANCE_FAILED notificationId="
                  + notification.notificationId().value()
                  + " errorType="
                  + error.getClass().getSimpleName());
          return Mono.empty();
        });
  }

  private static void logAttempts(final Notification notification, final int count) {
    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    for (final DeliveryAttempt attempt :
        attempts.subList(Math.max(0, attempts.size() - count), attempts.size())) {
      LOGGER.log(
          System.Logger.Level.INFO,
          "DISPATCH_ATTEMPT_RECORDED notificationId="
              + notification.notificationId().value()
              + " correlationId="
              + attempt.correlationId()
              + " providerId="
              + attempt.providerId().value()
              + " result="
              + attempt.result()
              + " failureCause="
              + attempt.failureCause()
              + " latencyMillis="
              + attempt.latencyMillis());
    }
  }

  private Mono<Void> publishEvents(
      final Notification notification,
      final ProviderId providerId,
      final List<DomainEvent> events) {
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
              metricsPort.errorRecorded(
                  ErrorCode.DISPATCH_EVENTS_NOT_PUBLISHED, notification.channelType(), providerId);
              return Mono.empty();
            });
  }

  private static FailureCause defaultCause(final AttemptResult result) {
    return switch (result) {
      case PERMANENT_FAILURE -> FailureCause.PROVIDER_REJECTED;
      case INVALID_RECIPIENT -> FailureCause.INVALID_RECIPIENT;
      case RECOVERABLE_FAILURE -> FailureCause.PROVIDER_TEMPORARY;
      case ACCEPTED -> null;
    };
  }

  private static FailureCause exhaustedCause(final FailureCause cause) {
    return cause == null || cause == FailureCause.PROVIDER_TEMPORARY
        ? FailureCause.RETRIES_EXHAUSTED
        : cause;
  }

  static AttemptRecord recordOf(
      final ProviderResult result, final AttemptOrigin origin, final String correlationId) {
    return recordOf(result, result.cause(), origin, correlationId);
  }

  private static AttemptRecord recordOf(
      final ProviderResult result,
      final FailureCause cause,
      final AttemptOrigin origin,
      final String correlationId) {
    return new AttemptRecord(
        result.result(),
        origin,
        result.providerId(),
        false,
        result.latencyMillis(),
        cause,
        correlationId,
        result.messageId());
  }

  private static boolean isTransportError(final Throwable error) {
    return !(error instanceof NullPointerException
        || error instanceof IllegalArgumentException
        || error instanceof ClassCastException
        || error instanceof IndexOutOfBoundsException
        || error instanceof ArithmeticException
        || error instanceof Error);
  }

  private static void applyOutcome(
      final Notification notification,
      final Outcome outcome,
      final RetryPolicy retryPolicy,
      final AttemptOrigin origin) {
    final String correlationId =
        notification.correlationId() == null ? null : notification.correlationId().value();
    for (final ProviderResult failedOver : outcome.failedOver()) {
      notification.recordFailedOver(recordOf(failedOver, origin, correlationId));
    }
    final ProviderResult last = outcome.last();
    final AttemptRecord record = recordOf(last, origin, correlationId);
    if (last.result() == AttemptResult.ACCEPTED) {
      notification.markDelivered(record);
    } else if (last.result() == AttemptResult.RECOVERABLE_FAILURE) {
      final int attemptNumber = notification.recoverableAttemptsInCurrentCycle() + 1;
      if (retryPolicy.shouldGiveUp(attemptNumber)) {
        notification.markRetriesExhausted(
            recordOf(last, exhaustedCause(last.cause()), origin, correlationId));
      } else {
        notification.markRecoverable(record);
      }
    } else {
      notification.markFailed(record);
    }
  }
}
