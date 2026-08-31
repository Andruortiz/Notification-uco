package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.TenantId;
import reactor.core.publisher.Mono;

public interface ChannelCatalogPort {

  Mono<ChannelRoute> findActiveRoute(ChannelType channel, TenantId tenantId);
}
