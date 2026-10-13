package co.edu.uco.notification.core.domain.configuration;

import co.edu.uco.notification.utils.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ParameterRegistry {

  public static final String DISPATCH_MAX_ATTEMPTS = "dispatch.max-attempts";
  public static final String REQUEUE_INTERVAL_MS = "requeue.interval-ms";
  public static final List<String> MANAGED_PROVIDERS = List.of("brevo", "twilio", "fcm");

  private final Map<String, ParameterDescriptor> descriptors;
  private final List<ParameterDescriptor> ordered;

  public ParameterRegistry() {
    this(List.of());
  }

  public ParameterRegistry(final List<ParameterDescriptor> additionalDescriptors) {
    Preconditions.requireNonNull(additionalDescriptors, "additionalDescriptors must not be null");
    final List<ParameterDescriptor> all = new ArrayList<>(initialDescriptors());
    all.addAll(additionalDescriptors);
    final Map<String, ParameterDescriptor> byKey = new LinkedHashMap<>();
    for (final ParameterDescriptor descriptor : all) {
      Preconditions.requireTrue(
          byKey.put(descriptor.key(), descriptor) == null,
          "duplicate descriptor key " + descriptor.key());
    }
    this.descriptors = Map.copyOf(byKey);
    this.ordered = List.copyOf(all);
  }

  public static String timeoutKey(final String providerId) {
    return "provider." + providerId + ".timeout-ms";
  }

  public static String connectTimeoutKey(final String providerId) {
    return "provider." + providerId + ".connect-timeout-ms";
  }

  public List<ParameterDescriptor> descriptors() {
    return ordered;
  }

  public Optional<ParameterDescriptor> find(final String key) {
    return Optional.ofNullable(descriptors.get(key));
  }

  private static List<ParameterDescriptor> initialDescriptors() {
    final List<ParameterDescriptor> initial = new ArrayList<>();
    initial.add(ParameterDescriptor.global(DISPATCH_MAX_ATTEMPTS, 3, 1, 20));
    for (final String providerId : MANAGED_PROVIDERS) {
      initial.add(
          ParameterDescriptor.forProvider(
              timeoutKey(providerId), providerId, 10_000, 1_000, 60_000));
      initial.add(
          ParameterDescriptor.forProvider(
              connectTimeoutKey(providerId), providerId, 5_000, 500, 30_000));
    }
    initial.add(ParameterDescriptor.global(REQUEUE_INTERVAL_MS, 30_000, 5_000, 600_000));
    return initial;
  }
}
