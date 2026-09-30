package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import reactor.core.publisher.Mono;

public interface NotificationBatchRepository {

  Mono<Void> save(BatchAcceptedResult result, TenantId tenantId);
}
