package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderCircuitOpenException;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.utils.Preconditions;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;

public class CircuitBreakerNotificationSender implements NotificationSenderPort {

  private static final class RecoverableFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private RecoverableFailure() {
      super("recoverable provider failure", null, false, false);
    }
  }

  private final NotificationSenderPort delegate;
  private final CircuitBreaker circuitBreaker;

  public CircuitBreakerNotificationSender(
      final NotificationSenderPort delegate, final ProviderCircuitProperties properties) {
    this.delegate = Preconditions.requireNonNull(delegate, "delegate must not be null");
    Preconditions.requireNonNull(properties, "properties must not be null");
    this.circuitBreaker =
        CircuitBreaker.of(
            delegate.providerId().value(),
            CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.failureRateThreshold())
                .minimumNumberOfCalls(properties.minimumCalls())
                .slidingWindowSize(properties.slidingWindowSize())
                .waitDurationInOpenState(properties.openWait())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .ignoreExceptions(ProviderDisabledException.class)
                .build());
  }

  @Override
  public Mono<ProviderResult> send(final Notification notification) {
    return Mono.defer(
        () -> {
          if (!circuitBreaker.tryAcquirePermission()) {
            return Mono.error(new ProviderCircuitOpenException(delegate.providerId()));
          }
          final long startedAt = System.nanoTime();
          return delegate
              .send(notification)
              .doOnSuccess(result -> record(result, System.nanoTime() - startedAt))
              .doOnError(
                  error ->
                      circuitBreaker.onError(
                          System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, error))
              .doOnCancel(circuitBreaker::releasePermission);
        });
  }

  private void record(final ProviderResult result, final long elapsedNanos) {
    if (result == null) {
      circuitBreaker.releasePermission();
    } else if (result.result() == AttemptResult.RECOVERABLE_FAILURE) {
      circuitBreaker.onError(elapsedNanos, TimeUnit.NANOSECONDS, new RecoverableFailure());
    } else {
      circuitBreaker.onSuccess(elapsedNanos, TimeUnit.NANOSECONDS);
    }
  }

  @Override
  public ProviderId providerId() {
    return delegate.providerId();
  }

  @Override
  public Optional<String> disabledReason() {
    return delegate.disabledReason();
  }

  @Override
  public boolean supportsAttachments() {
    return delegate.supportsAttachments();
  }
}
