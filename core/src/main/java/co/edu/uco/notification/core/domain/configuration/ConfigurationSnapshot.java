package co.edu.uco.notification.core.domain.configuration;

import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record ConfigurationSnapshot(
    long version,
    ConfigurationSource source,
    Map<String, Long> values,
    Instant adoptedAt,
    Set<String> pendingRestart) {

  public ConfigurationSnapshot {
    Preconditions.requireTrue(version >= 0, "version must not be negative");
    Preconditions.requireNonNull(source, "source must not be null");
    Preconditions.requireNonNull(values, "values must not be null");
    Preconditions.requireNonNull(adoptedAt, "adoptedAt must not be null");
    values = Map.copyOf(values);
    pendingRestart = pendingRestart == null ? Set.of() : Set.copyOf(pendingRestart);
  }

  public long require(final String key) {
    final Long value = values.get(key);
    Preconditions.requireNonNull(value, "no value for " + key);
    return value;
  }

  public long dispatchMaxAttempts() {
    return require(ParameterRegistry.DISPATCH_MAX_ATTEMPTS);
  }

  public long requeueIntervalMs() {
    return require(ParameterRegistry.REQUEUE_INTERVAL_MS);
  }

  public long providerTimeoutMs(final String providerId) {
    return require(ParameterRegistry.timeoutKey(providerId));
  }

  public long providerConnectTimeoutMs(final String providerId) {
    return require(ParameterRegistry.connectTimeoutKey(providerId));
  }
}
