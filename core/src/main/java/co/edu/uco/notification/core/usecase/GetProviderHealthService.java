package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.valueobject.ProviderHealth;
import co.edu.uco.notification.core.domain.valueobject.ProviderHealthReport;
import co.edu.uco.notification.core.domain.valueobject.ProviderHealthSettings;
import co.edu.uco.notification.core.domain.valueobject.ProviderHealthStatus;
import co.edu.uco.notification.core.domain.valueobject.ProviderLastError;
import co.edu.uco.notification.core.port.in.GetProviderHealthUseCase;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort.Counts;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort.ErrorMark;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort.Snapshot;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

public class GetProviderHealthService implements GetProviderHealthUseCase {

  private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

  private final ProviderMetricsQueryPort metricsQueryPort;
  private final ProviderHealthSettings settings;
  private final Clock clock;

  public GetProviderHealthService(
      final ProviderMetricsQueryPort metricsQueryPort,
      final ProviderHealthSettings settings,
      final Clock clock) {
    this.metricsQueryPort =
        Preconditions.requireNonNull(metricsQueryPort, "metricsQueryPort must not be null");
    this.settings = Preconditions.requireNonNull(settings, "settings must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<ProviderHealthReport> getHealth() {
    final Instant now = clock.instant();
    return metricsQueryPort
        .query(now, settings.window(), settings.lookback())
        .map(snapshot -> report(now, snapshot));
  }

  private ProviderHealthReport report(final Instant now, final Snapshot snapshot) {
    final List<ProviderHealth> providers =
        snapshot.counts().stream()
            .filter(c -> valid(c.provider()) && valid(c.channel()))
            .map(c -> health(c, snapshot))
            .sorted(
                Comparator.comparing(ProviderHealth::provider)
                    .thenComparing(ProviderHealth::channel))
            .toList();
    return new ProviderHealthReport(
        now, settings.window().toSeconds(), blankToNull(settings.grafanaUrl()), providers);
  }

  private ProviderHealth health(final Counts counts, final Snapshot snapshot) {
    final double volume = counts.delivered() + counts.failed();
    final Double rate = volume > 0 ? counts.delivered() / volume : null;
    final Double latency = snapshot.latencyP95SecondsByProvider().get(counts.provider());
    return new ProviderHealth(
        counts.provider(),
        counts.channel(),
        statusOf(counts, volume, rate),
        volume >= settings.minSamples() ? rate : null,
        Math.round(volume),
        volume >= settings.minSamples() && latency != null ? Math.round(latency * 1000) : null,
        lastErrorOf(counts, snapshot.errors()));
  }

  private ProviderHealthStatus statusOf(
      final Counts counts, final double volume, final Double rate) {
    if (volume < settings.minSamples() || rate == null) {
      return ProviderHealthStatus.UNKNOWN;
    }
    if (rate < settings.downThreshold()) {
      return ProviderHealthStatus.DOWN;
    }
    final double attempts = volume + counts.recoverable();
    final boolean retriesDominate =
        counts.recoverable() / attempts >= settings.recoverableThreshold();
    return rate < settings.healthyThreshold() || retriesDominate
        ? ProviderHealthStatus.DEGRADED
        : ProviderHealthStatus.HEALTHY;
  }

  private static ProviderLastError lastErrorOf(final Counts counts, final List<ErrorMark> errors) {
    return errors.stream()
        .filter(e -> counts.provider().equals(e.provider()) && counts.channel().equals(e.channel()))
        .max(
            Comparator.comparing(ErrorMark::at)
                .thenComparing(e -> "failed".equals(e.result()) ? 1 : 0))
        .map(e -> new ProviderLastError(e.result(), e.at()))
        .orElse(null);
  }

  private static boolean valid(final String label) {
    return label != null && LABEL.matcher(label).matches();
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
