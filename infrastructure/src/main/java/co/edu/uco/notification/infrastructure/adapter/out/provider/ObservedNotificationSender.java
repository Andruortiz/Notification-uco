package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.adapter.out.metrics.MicrometerNotificationMetrics;
import co.edu.uco.notification.infrastructure.config.ReactorObservations;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import reactor.core.publisher.Mono;

public class ObservedNotificationSender implements NotificationSenderPort {

  static final String OBSERVATION_NAME = "notification.provider.call";

  private final NotificationSenderPort delegate;
  private final ObservationRegistry observationRegistry;

  public ObservedNotificationSender(
      final NotificationSenderPort delegate, final ObservationRegistry observationRegistry) {
    this.delegate = Preconditions.requireNonNull(delegate, "delegate must not be null");
    this.observationRegistry =
        Preconditions.requireNonNull(observationRegistry, "observationRegistry must not be null");
  }

  @Override
  public Mono<AttemptResult> send(final Notification notification) {
    return Mono.deferContextual(
        context -> {
          final Observation.Context observationContext = new Observation.Context();
          final Object correlationId = context.getOrDefault(CorrelationId.CONTEXT_KEY, null);
          if (correlationId != null) {
            observationContext.put(CorrelationId.CONTEXT_KEY, correlationId.toString());
          }
          final Observation parent = ReactorObservations.from(context);
          final Observation created =
              Observation.createNotStarted(
                  OBSERVATION_NAME, () -> observationContext, observationRegistry);
          if (parent != null) {
            created.parentObservation(parent);
          }
          final Observation observation =
              created
                  .lowCardinalityKeyValue("provider", delegate.providerId().value())
                  .lowCardinalityKeyValue("channel", notification.channelType().value())
                  .start();
          return delegate
              .send(notification)
              .doOnNext(
                  result ->
                      observation.lowCardinalityKeyValue(
                          "result", MicrometerNotificationMetrics.resultLabel(result)))
              .doOnError(observation::error)
              .doFinally(signal -> observation.stop())
              .contextWrite(inner -> ReactorObservations.with(inner, observation));
        });
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
