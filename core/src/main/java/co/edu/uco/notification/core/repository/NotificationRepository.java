package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.TenantId;
import reactor.core.publisher.Mono;

public interface NotificationRepository {

  Mono<Notification> save(Notification notification);

  Mono<Notification> findById(NotificationId notificationId);

  Mono<Notification> findByTenantAndExternalId(TenantId tenantId, ExternalId externalId);
}
