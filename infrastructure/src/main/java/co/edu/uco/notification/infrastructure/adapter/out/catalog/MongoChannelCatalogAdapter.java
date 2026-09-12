package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class MongoChannelCatalogAdapter implements ChannelCatalogPort {

  private final ChannelCatalogCache cache;

  public MongoChannelCatalogAdapter(final ChannelCatalogCache cache) {
    this.cache = Preconditions.requireNonNull(cache, "cache must not be null");
  }

  @Override
  public Mono<ChannelRoute> findActiveRoute(final ChannelType channel, final TenantId tenantId) {
    Preconditions.requireNonNull(channel, "channel must not be null");
    return Mono.justOrEmpty(cache.snapshot().get(channel.value().toUpperCase()));
  }
}
