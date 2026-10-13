package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.usecase.ConfigurationHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationVersionMetricTest {

  private static ConfigurationSnapshot snapshot(
      final long version, final ConfigurationSource source) {
    return new ConfigurationSnapshot(
        version,
        source,
        Map.of("dispatch.max-attempts", 3L),
        Instant.parse("2026-10-05T10:00:00Z"),
        Set.of());
  }

  private static double gauge(
      final SimpleMeterRegistry registry, final ConfigurationSource source) {
    return registry
        .get(ConfigurationConfig.VERSION_METRIC)
        .tag("source", source.name())
        .gauge()
        .value();
  }

  @Test
  void theGaugeReflectsTheVersionUnderTheSourceInUseAndFollowsEveryReplacement() {
    final ConfigurationHolder holder =
        new ConfigurationHolder(snapshot(0, ConfigurationSource.DEFAULTS));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new ConfigurationConfig().configurationVersionMetric(holder).bindTo(registry);

    assertEquals(0, gauge(registry, ConfigurationSource.DEFAULTS));
    assertEquals(0, gauge(registry, ConfigurationSource.PARAMETERS));

    holder.replace(snapshot(7, ConfigurationSource.PARAMETERS));

    assertEquals(7, gauge(registry, ConfigurationSource.PARAMETERS));
    assertEquals(0, gauge(registry, ConfigurationSource.DEFAULTS));
    assertEquals(0, gauge(registry, ConfigurationSource.LAST_KNOWN));

    holder.replace(snapshot(5, ConfigurationSource.LAST_KNOWN));

    assertEquals(5, gauge(registry, ConfigurationSource.LAST_KNOWN));
    assertEquals(0, gauge(registry, ConfigurationSource.PARAMETERS));
  }
}
