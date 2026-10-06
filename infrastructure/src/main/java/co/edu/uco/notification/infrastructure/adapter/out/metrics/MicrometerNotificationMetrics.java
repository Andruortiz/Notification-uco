package co.edu.uco.notification.infrastructure.adapter.out.metrics;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class MicrometerNotificationMetrics implements NotificationMetricsPort {

  static final String ACCEPTED = "notification.accepted";
  static final String ATTEMPTS = "notification.attempts";
  static final String DISPATCHED = "notification.dispatched";
  static final String PROVIDER_DURATION = "notification.provider.duration";
  static final String ERRORS = "notification.errors";

  private static final String NONE = "none";

  private final MeterRegistry registry;

  public MicrometerNotificationMetrics(final MeterRegistry registry) {
    this.registry = Preconditions.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public void notificationAccepted(final ChannelType channel) {
    registry.counter(ACCEPTED, "channel", channel.value()).increment();
  }

  @Override
  public void dispatchAttempted(
      final ChannelType channel, final ProviderId provider, final AttemptResult result) {
    registry
        .counter(ATTEMPTS, "channel", channel.value(), "provider", provider.value())
        .increment();
    registry
        .counter(
            DISPATCHED,
            "channel",
            channel.value(),
            "provider",
            provider.value(),
            "result",
            resultLabel(result))
        .increment();
  }

  @Override
  public void providerCalled(
      final ProviderId provider, final AttemptResult result, final Duration duration) {
    Timer.builder(PROVIDER_DURATION)
        .tag("provider", provider.value())
        .tag("result", resultLabel(result))
        .publishPercentileHistogram()
        .register(registry)
        .record(duration);
  }

  @Override
  public void errorRecorded(final ErrorCode errorCode) {
    errorRecorded(errorCode, null, null);
  }

  @Override
  public void errorRecorded(
      final ErrorCode errorCode, final ChannelType channel, final ProviderId provider) {
    registry
        .counter(
            ERRORS,
            "errorCode",
            errorCode.format(),
            "failureCategory",
            errorCode.category().name(),
            "channel",
            channel == null ? NONE : channel.value(),
            "provider",
            provider == null ? NONE : provider.value())
        .increment();
  }

  private static String resultLabel(final AttemptResult result) {
    return switch (result) {
      case ACCEPTED -> "delivered";
      case RECOVERABLE_FAILURE -> "recoverable";
      case PERMANENT_FAILURE -> "failed";
    };
  }
}
