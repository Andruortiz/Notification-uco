package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@EnableConfigurationProperties(ChannelCatalogProperties.class)
public class ConfigurationChannelCatalogAdapter implements ChannelCatalogPort {

  private final ChannelCatalogProperties properties;

  public ConfigurationChannelCatalogAdapter(final ChannelCatalogProperties properties) {
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
  }

  @Override
  public Mono<ChannelRoute> findActiveRoute(final ChannelType channel, final TenantId tenantId) {
    Preconditions.requireNonNull(channel, "channel must not be null");

    final Map<String, ChannelCatalogProperties.ChannelEntry> channels = properties.channels();
    if (channels == null) {
      return Mono.empty();
    }

    return channels.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(channel.value()))
        .map(Map.Entry::getValue)
        .findFirst()
        .flatMap(entry -> toRoute(channel, entry))
        .map(Mono::just)
        .orElseGet(Mono::empty);
  }

  private static Optional<ChannelRoute> toRoute(
      final ChannelType channel, final ChannelCatalogProperties.ChannelEntry entry) {
    if (entry.providers() == null || entry.providers().isEmpty()) {
      return Optional.empty();
    }
    final List<ProviderId> providers = entry.providers().stream().map(ProviderId::of).toList();
    return Optional.of(new ChannelRoute(channel, providers, entry.contentSchema()));
  }
}
