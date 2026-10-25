package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import co.edu.uco.notification.core.domain.valueobject.ProviderHealth;
import co.edu.uco.notification.core.domain.valueobject.ProviderHealthReport;
import co.edu.uco.notification.core.domain.valueobject.ProviderHealthSettings;
import co.edu.uco.notification.core.domain.valueobject.ProviderHealthStatus;
import co.edu.uco.notification.core.exception.ProviderMetricsUnavailableException;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort.Counts;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort.ErrorMark;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetProviderHealthServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static GetProviderHealthService serviceWith(
      final Mono<Snapshot> snapshot, final String grafanaUrl) {
    return new GetProviderHealthService(
        (now, window, lookback) -> snapshot,
        new ProviderHealthSettings(
            Duration.ofMinutes(5), Duration.ofMinutes(15), 5, 0.95, 0.5, 0.5, grafanaUrl),
        CLOCK);
  }

  private static ProviderHealth only(final Snapshot snapshot) {
    final ProviderHealthReport report = serviceWith(Mono.just(snapshot), null).getHealth().block();
    assertEquals(1, report.providers().size());
    return report.providers().get(0);
  }

  private static Snapshot snapshotOf(final Counts counts, final ErrorMark... errors) {
    return new Snapshot(List.of(counts), Map.of("brevo", 0.25), List.of(errors));
  }

  @Test
  void healthyWhenTheRateIsAtLeastTheHealthyThreshold() {
    final ProviderHealth health = only(snapshotOf(new Counts("brevo", "EMAIL", 99, 1, 0)));

    assertEquals(ProviderHealthStatus.HEALTHY, health.status());
    assertEquals(0.99, health.successRate());
    assertEquals(100, health.sampleCount());
    assertEquals(250L, health.latencyP95Millis());
    assertNull(health.lastError());
  }

  @Test
  void degradedBetweenTheThresholds() {
    assertEquals(
        ProviderHealthStatus.DEGRADED,
        only(snapshotOf(new Counts("brevo", "EMAIL", 8, 2, 0))).status());
  }

  @Test
  void downBelowTheDownThresholdAndLastErrorIsFailedOverRecoverableInTheSameStep() {
    final Instant at = NOW.minusSeconds(60);
    final ProviderHealth health =
        only(
            snapshotOf(
                new Counts("brevo", "EMAIL", 1, 9, 0),
                new ErrorMark("brevo", "EMAIL", "recoverable", at),
                new ErrorMark("brevo", "EMAIL", "failed", at),
                new ErrorMark("brevo", "EMAIL", "recoverable", at.minusSeconds(120))));

    assertEquals(ProviderHealthStatus.DOWN, health.status());
    assertEquals("failed", health.lastError().result());
    assertEquals(at, health.lastError().at());
  }

  @Test
  void unknownWithoutVolumeEvenWhenRecoverableIsHigh() {
    final ProviderHealth health = only(snapshotOf(new Counts("brevo", "EMAIL", 1, 1, 50)));

    assertEquals(ProviderHealthStatus.UNKNOWN, health.status());
    assertNull(health.successRate());
    assertNull(health.latencyP95Millis());
  }

  @Test
  void recoverableAttemptsAreExcludedFromTheRateButDegradeWhenTheyDominate() {
    final ProviderHealth health = only(snapshotOf(new Counts("brevo", "EMAIL", 10, 0, 10)));

    assertEquals(1.0, health.successRate());
    assertEquals(10, health.sampleCount());
    assertEquals(ProviderHealthStatus.DEGRADED, health.status());
  }

  @Test
  void discardsInvalidLabelsAndRepeatsLatencyPerChannel() {
    final Snapshot snapshot =
        new Snapshot(
            List.of(
                new Counts("brevo", "SMS", 10, 0, 0),
                new Counts("bad provider", "EMAIL", 10, 0, 0),
                new Counts("brevo", "EMAIL", 10, 0, 0),
                new Counts("brevo", "../x", 10, 0, 0)),
            Map.of("brevo", 0.1),
            List.of());

    final ProviderHealthReport report =
        serviceWith(Mono.just(snapshot), "http://grafana/d/x").getHealth().block();

    assertEquals(
        List.of("EMAIL", "SMS"), report.providers().stream().map(ProviderHealth::channel).toList());
    assertEquals(100L, report.providers().get(0).latencyP95Millis());
    assertEquals(100L, report.providers().get(1).latencyP95Millis());
    assertEquals("http://grafana/d/x", report.grafanaUrl());
    assertEquals(300, report.windowSeconds());
    assertEquals(NOW, report.generatedAt());
  }

  @Test
  void propagatesTheUnavailableSourceError() {
    StepVerifier.create(
            serviceWith(Mono.error(new ProviderMetricsUnavailableException()), " ").getHealth())
        .expectError(ProviderMetricsUnavailableException.class)
        .verify();
  }
}
