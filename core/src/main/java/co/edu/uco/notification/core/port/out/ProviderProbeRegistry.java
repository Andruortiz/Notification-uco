package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ProviderProbeRegistry {

  private final Map<ProviderId, ProviderProbePort> probesByProviderId;

  public ProviderProbeRegistry(final Collection<ProviderProbePort> probes) {
    Preconditions.requireNonNull(probes, "probes must not be null");
    final Map<ProviderId, ProviderProbePort> byProviderId = new HashMap<>();
    for (final ProviderProbePort probe : probes) {
      Preconditions.requireNonNull(probe, "probe must not be null");
      final ProviderId providerId =
          Preconditions.requireNonNull(probe.providerId(), "probe providerId must not be null");
      Preconditions.requireTrue(
          byProviderId.putIfAbsent(providerId, probe) == null,
          "Duplicate provider probe for provider: " + providerId.value());
    }
    this.probesByProviderId = Map.copyOf(byProviderId);
  }

  public Optional<ProviderProbePort> find(final ProviderId providerId) {
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    return Optional.ofNullable(probesByProviderId.get(providerId));
  }
}
