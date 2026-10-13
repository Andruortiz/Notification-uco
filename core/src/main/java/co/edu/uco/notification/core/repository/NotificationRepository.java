package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface NotificationRepository {

  Mono<Notification> save(Notification notification);

  Mono<Notification> findById(NotificationId notificationId);

  Mono<Notification> findByTenantAndExternalId(TenantId tenantId, ExternalId externalId);

  Flux<Notification> findByStatus(NotificationStatus status);

  Flux<Notification> search(NotificationSearchCriteria criteria);

  Mono<Notification> reserveForDispatch(NotificationId notificationId);

  Mono<Notification> releaseReservation(NotificationId notificationId);

  Flux<Notification> claimForRequeue(Instant threshold, int limit);

  Flux<Notification> claimStuckInProcess(Instant threshold, int limit);
}
