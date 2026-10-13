package co.edu.uco.notification.core.domain.configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ConfigurationFixtures {

  public static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

  private ConfigurationFixtures() {}

  public static ConfigurationSnapshot defaults(final ParameterRegistry registry) {
    final Map<String, Long> values = new LinkedHashMap<>();
    for (final ParameterDescriptor descriptor : registry.descriptors()) {
      values.put(descriptor.key(), descriptor.defaultValue());
    }
    return new ConfigurationSnapshot(0, ConfigurationSource.DEFAULTS, values, NOW, Set.of());
  }

  public static ConfigurationSnapshot defaults() {
    return defaults(new ParameterRegistry());
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

  public static FixedConfiguration fixedWith(
      final Map<String, Set<String>> enabledProvidersByChannel,
      final long scanTimeoutMs,
      final int scanMaxAttempts,
      final long sweepWindowMs,
      final Map<String, Map<String, Long>> contentLimits) {
    final FixedConfiguration base = fixed();
    return new FixedConfiguration(
        enabledProvidersByChannel,
        base.configuredProviders(),
        scanTimeoutMs,
        scanMaxAttempts,
        sweepWindowMs,
        contentLimits,
        base.providerContentLimits());
  }

  public static FixedConfiguration fixedWithoutProvider(final String providerId) {
    final FixedConfiguration base = fixed();
    final Set<String> remaining = new java.util.HashSet<>(base.configuredProviders());
    remaining.remove(providerId);
    return new FixedConfiguration(
        base.enabledProvidersByChannel(),
        remaining,
        base.attachmentScanTimeoutMs(),
        base.attachmentScanMaxAttempts(),
        base.attachmentSweepWindowMs(),
        base.contentLimitsByChannel(),
        base.providerContentLimits());
  }

  public static ConfigurationChange change(final long version, final Object... keyValuePairs) {
    final Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < keyValuePairs.length; index += 2) {
      values.put((String) keyValuePairs[index], keyValuePairs[index + 1]);
    }
    return new ConfigurationChange(version, values);
  }
}
