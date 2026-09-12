package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class NotificationMongoAdapter implements NotificationRepository {

  private final ReactiveMongoTemplate mongoTemplate;

  public NotificationMongoAdapter(final ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
  }

  @Override
  public Mono<Notification> save(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    return mongoTemplate
        .save(NotificationDocumentMapper.toDocument(notification))
        .map(NotificationDocumentMapper::toDomain)
        .onErrorMap(
            OptimisticLockingFailureException.class,
            error -> new NotificationVersionConflictException(notification.notificationId()));
  }

  @Override
  public Mono<Notification> findById(final NotificationId notificationId) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    return mongoTemplate
        .findById(notificationId.value(), NotificationDocument.class)
        .map(NotificationDocumentMapper::toDomain);
  }

  @Override
  public Mono<Notification> findByTenantAndExternalId(
      final TenantId tenantId, final ExternalId externalId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    final Query query =
        Query.query(
            Criteria.where("tenantId")
                .is(tenantId.value())
                .and("externalId")
                .is(externalId.value()));
    return mongoTemplate
        .findOne(query, NotificationDocument.class)
        .map(NotificationDocumentMapper::toDomain);
  }

  @Override
  public Flux<Notification> findByStatus(final NotificationStatus status) {
    Preconditions.requireNonNull(status, "status must not be null");
    final Query query = Query.query(Criteria.where("status").is(status));
    return mongoTemplate
        .find(query, NotificationDocument.class)
        .map(NotificationDocumentMapper::toDomain);
  }
}
