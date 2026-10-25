package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.UndeliverableRecipient;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Mono;

public interface UndeliverableRecipientRepository {

  Mono<Boolean> markIfAbsent(UndeliverableRecipient recipient);

  Mono<UndeliverableRecipient> find(
      TenantId tenantId, ChannelType channelType, String normalizedAddress);

  Mono<Void> remove(TenantId tenantId, ChannelType channelType, String normalizedAddress);
}
