package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Mono;

public interface FindUndeliverableMarkQuery {

  Mono<UndeliverableMarkView> find(TenantId tenantId, ChannelType channelType, String address);
}
