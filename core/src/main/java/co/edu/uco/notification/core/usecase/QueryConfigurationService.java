package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.port.in.ConfigurationDescription;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.ParameterDescription;
import co.edu.uco.notification.core.port.in.QueryConfigurationUseCase;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import reactor.core.publisher.Mono;

public final class QueryConfigurationService implements QueryConfigurationUseCase {

  private final ConfigurationView configurationView;
  private final ParameterRegistry registry;

  public QueryConfigurationService(
      final ConfigurationView configurationView, final ParameterRegistry registry) {
    this.configurationView =
        Preconditions.requireNonNull(configurationView, "configurationView must not be null");
    this.registry = Preconditions.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public Mono<ConfigurationDescription> describe() {
    return Mono.fromSupplier(this::describeCurrent);
  }

  private ConfigurationDescription describeCurrent() {
    final ConfigurationSnapshot snapshot = configurationView.snapshot();
    final List<ParameterDescription> parameters =
        registry.descriptors().stream()
            .map(
                descriptor ->
                    new ParameterDescription(
                        descriptor,
                        snapshot
                            .values()
                            .getOrDefault(descriptor.key(), descriptor.defaultValue())))
            .toList();
    return new ConfigurationDescription(
        snapshot.version(),
        snapshot.source(),
        snapshot.adoptedAt(),
        snapshot.pendingRestart(),
        parameters);
  }
}
