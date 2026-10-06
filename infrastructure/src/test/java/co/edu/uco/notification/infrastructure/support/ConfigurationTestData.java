package co.edu.uco.notification.infrastructure.support;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.FixedConfiguration;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ConfigurationTestData {

  private ConfigurationTestData() {}

  public static ConfigurationSnapshot defaults() {
    final Map<String, Long> values = new LinkedHashMap<>();
    for (final ParameterDescriptor descriptor : new ParameterRegistry().descriptors()) {
      values.put(descriptor.key(), descriptor.defaultValue());
    }
    return new ConfigurationSnapshot(
        0, ConfigurationSource.DEFAULTS, values, Instant.parse("2026-10-05T10:00:00Z"), Set.of());
  }

  public static FixedConfiguration fixed() {
    return new FixedConfiguration(
        Map.of(
            "EMAIL", Set.of("simulated", "brevo"),
            "SMS", Set.of("simulated", "twilio"),
            "PUSH", Set.of("simulated", "fcm")),
        Set.of("simulated", "brevo", "twilio", "fcm"),
        10_000,
        3,
        3_600_000,
        Map.of("SMS", Map.of("body.maxLength", 160L)),
        Map.of("twilio", Map.of("SMS", Map.of("body.maxLength", 1_600L))));
  }

  public static ConfigurationChange change(final long version, final Object... keyValuePairs) {
    final Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < keyValuePairs.length; index += 2) {
      values.put((String) keyValuePairs[index], keyValuePairs[index + 1]);
    }
    return new ConfigurationChange(version, values);
  }
}
