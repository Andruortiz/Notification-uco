package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import co.edu.uco.notification.utils.Preconditions;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
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

  @Override
  public Flux<Notification> search(final NotificationSearchCriteria criteria) {
    Preconditions.requireNonNull(criteria, "criteria must not be null");
    final List<Criteria> conditions = new ArrayList<>();
    conditions.add(Criteria.where("tenantId").is(criteria.tenantId().value()));
    if (criteria.recipientId() != null) {
      conditions.add(Criteria.where("recipientId").is(criteria.recipientId().value()));
    }
    if (criteria.channelType() != null) {
      conditions.add(Criteria.where("channelType").is(criteria.channelType().value()));
    }
    if (criteria.status() != null) {
      conditions.add(Criteria.where("status").is(criteria.status()));
    }
    if (criteria.from() != null) {
      conditions.add(Criteria.where("acceptedAt").gte(criteria.from()));
    }
    if (criteria.to() != null) {
      conditions.add(Criteria.where("acceptedAt").lte(criteria.to()));
    }
    final Query query =
        Query.query(new Criteria().andOperator(conditions.toArray(new Criteria[0])))
            .with(Sort.by(Sort.Direction.DESC, "acceptedAt"))
            .skip(criteria.offset())
            .limit(criteria.limit() + 1);
    return mongoTemplate
        .find(query, NotificationDocument.class)
        .map(NotificationDocumentMapper::toDomain);
  }
}
