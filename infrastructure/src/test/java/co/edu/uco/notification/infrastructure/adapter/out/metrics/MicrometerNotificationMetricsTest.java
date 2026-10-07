package co.edu.uco.notification.infrastructure.adapter.out.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MicrometerNotificationMetricsTest {

  private static final ChannelType EMAIL = ChannelType.of("EMAIL");
  private static final ProviderId BREVO = ProviderId.of("brevo");
  private static final Set<String> FORBIDDEN_TAGS =
      Set.of(
          "tenantId",
          "notificationId",
          "correlationId",
          "recipient",
          "content",
          "credential",
          "token");

  private SimpleMeterRegistry registry;
  private MicrometerNotificationMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new MicrometerNotificationMetrics(registry);
  }

  private double count(final String name, final String... tags) {
    final Counter counter = registry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void acceptedIsCountedPerChannel() {
    metrics.notificationAccepted(EMAIL);
    metrics.notificationAccepted(EMAIL);
    metrics.notificationAccepted(ChannelType.of("SMS"));

    assertEquals(2, count("notification.accepted", "channel", "EMAIL"));
    assertEquals(1, count("notification.accepted", "channel", "SMS"));
  }

  @Test
  void aDispatchAttemptCountsAttemptsAndTheMappedResult() {
    metrics.dispatchAttempted(EMAIL, BREVO, AttemptResult.ACCEPTED);
    metrics.dispatchAttempted(EMAIL, BREVO, AttemptResult.RECOVERABLE_FAILURE);
    metrics.dispatchAttempted(EMAIL, BREVO, AttemptResult.PERMANENT_FAILURE);
    metrics.dispatchAttempted(EMAIL, BREVO, AttemptResult.ACCEPTED);

    assertEquals(4, count("notification.attempts", "channel", "EMAIL", "provider", "brevo"));
    assertEquals(2, dispatched("delivered"));
    assertEquals(1, dispatched("recoverable"));
    assertEquals(1, dispatched("failed"));
    assertEquals(0, dispatched("discarded"));
  }

  private double dispatched(final String result) {
    return count(
        "notification.dispatched", "channel", "EMAIL", "provider", "brevo", "result", result);
  }

  @Test
  void theProviderTimerRecordsTheDurationPerProviderAndResultWithAHistogram() {
    final PrometheusMeterRegistry prometheus =
        new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    final MicrometerNotificationMetrics histogramMetrics =
        new MicrometerNotificationMetrics(prometheus);
    histogramMetrics.providerCalled(BREVO, AttemptResult.ACCEPTED, Duration.ofMillis(250));
    histogramMetrics.providerCalled(BREVO, AttemptResult.ACCEPTED, Duration.ofMillis(750));

    final Timer timer =
        prometheus
            .find("notification.provider.duration")
            .tags("provider", "brevo", "result", "delivered")
            .timer();
    assertNotNull(timer);
    assertEquals(2, timer.count());
    assertEquals(1000, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    assertTrue(timer.takeSnapshot().histogramCounts().length > 0);
  }

  @Test
  void anErrorWithoutDispatchContextCarriesNoneForChannelAndProvider() {
    metrics.errorRecorded(ErrorCode.REQUEUE_FAILED);

    assertEquals(
        1,
        count(
            "notification.errors",
            "errorCode",
            ErrorCode.REQUEUE_FAILED.format(),
            "failureCategory",
            ErrorCode.REQUEUE_FAILED.category().name(),
            "channel",
            "none",
            "provider",
            "none"));
  }

  @Test
  void anErrorInDispatchCarriesChannelAndProvider() {
    metrics.errorRecorded(ErrorCode.DISPATCH_EVENTS_NOT_PUBLISHED, EMAIL, BREVO);
    metrics.errorRecorded(ErrorCode.DISPATCH_RESERVATION_NOT_RELEASED, EMAIL, null);

    assertEquals(
        1,
        count(
            "notification.errors",
            "errorCode",
            ErrorCode.DISPATCH_EVENTS_NOT_PUBLISHED.format(),
            "channel",
            "EMAIL",
            "provider",
            "brevo"));
    assertEquals(
        1,
        count(
            "notification.errors",
            "errorCode",
            ErrorCode.DISPATCH_RESERVATION_NOT_RELEASED.format(),
            "channel",
            "EMAIL",
            "provider",
            "none"));
  }

  @Test
  void everyMeterUsesOnlyTheClosedTagSetOfItsFamilyAndNeverAForbiddenTag() {
    metrics.notificationAccepted(EMAIL);
    metrics.dispatchAttempted(EMAIL, BREVO, AttemptResult.ACCEPTED);
    metrics.providerCalled(BREVO, AttemptResult.ACCEPTED, Duration.ofMillis(5));
    metrics.errorRecorded(ErrorCode.REQUEUE_FAILED);
    metrics.errorRecorded(ErrorCode.DISPATCH_EVENTS_NOT_PUBLISHED, EMAIL, BREVO);

    final List<Meter> meters = registry.getMeters();
    assertTrue(meters.size() >= 5);
    for (final Meter meter : meters) {
      final Set<String> keys =
          meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
      assertTrue(Collections.disjoint(keys, FORBIDDEN_TAGS), meter.getId().getName());
      assertEquals(expectedKeys(meter.getId().getName()), keys, meter.getId().getName());
    }
  }

  private static Set<String> expectedKeys(final String name) {
    return switch (name) {
      case "notification.accepted" -> Set.of("channel");
      case "notification.attempts" -> Set.of("channel", "provider");
      case "notification.dispatched" -> Set.of("channel", "provider", "result");
      case "notification.provider.duration" -> Set.of("provider", "result");
      case "notification.errors" -> Set.of("errorCode", "failureCategory", "channel", "provider");
      default -> throw new AssertionError("unexpected meter " + name);
    };
  }

  @Test
  void constructorRejectsANullRegistry() {
    assertThrows(NullPointerException.class, () -> new MicrometerNotificationMetrics(null));
  }
}
