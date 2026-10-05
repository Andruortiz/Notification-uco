package co.edu.uco.notification.core.domain.configuration;

import co.edu.uco.notification.utils.Preconditions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public record FixedConfiguration(
    Map<String, Set<String>> enabledProvidersByChannel,
    Set<String> configuredProviders,
    long attachmentScanTimeoutMs,
    int attachmentScanMaxAttempts,
    long attachmentSweepWindowMs,
    Map<String, Map<String, Long>> contentLimitsByChannel,
    Map<String, Map<String, Map<String, Long>>> providerContentLimits) {

  public FixedConfiguration {
    enabledProvidersByChannel = copySets(enabledProvidersByChannel);
    Preconditions.requireNonNull(configuredProviders, "configuredProviders must not be null");
    configuredProviders = Set.copyOf(configuredProviders);
    contentLimitsByChannel = copyLimits(contentLimitsByChannel);
    providerContentLimits = copyNested(providerContentLimits);
  }

  @Override
  public Map<String, Set<String>> enabledProvidersByChannel() {
    return copySets(enabledProvidersByChannel);
  }

  @Override
  public Map<String, Map<String, Long>> contentLimitsByChannel() {
    return copyLimits(contentLimitsByChannel);
  }

  @Override
  public Map<String, Map<String, Map<String, Long>>> providerContentLimits() {
    return copyNested(providerContentLimits);
  }

  private static Map<String, Set<String>> copySets(final Map<String, Set<String>> source) {
    Preconditions.requireNonNull(source, "enabledProvidersByChannel must not be null");
    final Map<String, Set<String>> copy = new HashMap<>();
    source.forEach((channel, providers) -> copy.put(channel, Set.copyOf(providers)));
    return Map.copyOf(copy);
  }

  private static Map<String, Map<String, Long>> copyLimits(
      final Map<String, Map<String, Long>> source) {
    Preconditions.requireNonNull(source, "limits must not be null");
    final Map<String, Map<String, Long>> copy = new HashMap<>();
    source.forEach((channel, limits) -> copy.put(channel, Map.copyOf(limits)));
    return Map.copyOf(copy);
  }

  private static Map<String, Map<String, Map<String, Long>>> copyNested(
      final Map<String, Map<String, Map<String, Long>>> source) {
    Preconditions.requireNonNull(source, "providerContentLimits must not be null");
    final Map<String, Map<String, Map<String, Long>>> copy = new HashMap<>();
    source.forEach((provider, byChannel) -> copy.put(provider, copyLimits(byChannel)));
    return Map.copyOf(copy);
  }
}
