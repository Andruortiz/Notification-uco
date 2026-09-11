package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface NotificationRepository {

  Mono<Notification> save(Notification notification);

  Mono<Notification> findById(NotificationId notificationId);

  Mono<Notification> findByTenantAndExternalId(TenantId tenantId, ExternalId externalId);

  Flux<Notification> findByStatus(NotificationStatus status);
}
