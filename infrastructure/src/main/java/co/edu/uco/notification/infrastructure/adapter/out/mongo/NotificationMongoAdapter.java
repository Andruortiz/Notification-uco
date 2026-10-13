package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.NotificationAlreadyAcceptedException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
    return Mono.defer(() -> mongoTemplate.save(NotificationDocumentMapper.toDocument(notification)))
        .map(NotificationDocumentMapper::toDomain)
        .onErrorMap(
            OptimisticLockingFailureException.class,
            error -> new NotificationVersionConflictException(notification.notificationId()))
        .onErrorMap(
            DuplicateKeyException.class,
            error ->
                new NotificationAlreadyAcceptedException(
                    notification.tenantId(), notification.externalId()));
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
    query.fields().exclude("attachments");
    return mongoTemplate
        .find(query, NotificationDocument.class)
        .map(NotificationDocumentMapper::toDomain);
  }

  @Override
  public Mono<Notification> reserveForDispatch(final NotificationId notificationId) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    return Mono.defer(
        () -> {
          final Query query =
              Query.query(
                  Criteria.where("_id")
                      .is(notificationId.value())
                      .and("status")
                      .is(NotificationStatus.PENDING));
          final Update update =
              new Update()
                  .set("status", NotificationStatus.IN_PROCESS)
                  .set("dispatchReservedAt", Instant.now())
                  .inc("version", 1);
          return findAndModify(query, update);
        });
  }

  @Override
  public Mono<Notification> releaseReservation(final NotificationId notificationId) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    return Mono.defer(
        () -> {
          final Query query =
              Query.query(
                  Criteria.where("_id")
                      .is(notificationId.value())
                      .and("status")
                      .is(NotificationStatus.IN_PROCESS));
          final Update update =
              new Update()
                  .set("status", NotificationStatus.PENDING)
                  .set("pendingSince", Instant.now())
                  .unset("dispatchReservedAt")
                  .inc("version", 1);
          return findAndModify(query, update);
        });
  }

  @Override
  public Flux<Notification> claimForRequeue(final Instant threshold, final int limit) {
    Preconditions.requireNonNull(threshold, "threshold must not be null");
    Preconditions.requireTrue(limit > 0, "limit must be positive");
    return claimUpTo(
        () -> {
          final Criteria stale =
              new Criteria()
                  .orOperator(
                      Criteria.where("pendingSince").lt(threshold),
                      Criteria.where("pendingSince").is(null).and("acceptedAt").lt(threshold));
          final Query query =
              Query.query(
                      new Criteria()
                          .andOperator(
                              Criteria.where("status").is(NotificationStatus.PENDING), stale))
                  .with(Sort.by(Sort.Direction.ASC, "pendingSince"));
          final Update update = new Update().set("pendingSince", Instant.now()).inc("version", 1);
          return findAndModify(query, update);
        },
        limit);
  }

  @Override
  public Flux<Notification> claimStuckInProcess(final Instant threshold, final int limit) {
    Preconditions.requireNonNull(threshold, "threshold must not be null");
    Preconditions.requireTrue(limit > 0, "limit must be positive");
    return claimUpTo(
        () -> {
          final Query query =
              Query.query(
                      Criteria.where("status")
                          .is(NotificationStatus.IN_PROCESS)
                          .and("dispatchReservedAt")
                          .lt(threshold))
                  .with(Sort.by(Sort.Direction.ASC, "dispatchReservedAt"));
          final Update update =
              new Update()
                  .set("status", NotificationStatus.RECOVERABLE)
                  .unset("dispatchReservedAt")
                  .inc("version", 1);
          return findAndModify(query, update);
        },
        limit);
  }

  private Mono<Notification> findAndModify(final Query query, final Update update) {
    return mongoTemplate
        .findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true),
            NotificationDocument.class)
        .map(NotificationDocumentMapper::toDomain);
  }

  private static Flux<Notification> claimUpTo(
      final Supplier<Mono<Notification>> claim, final int remaining) {
    if (remaining <= 0) {
      return Flux.empty();
    }
    return Mono.defer(claim)
        .flatMapMany(
            claimed ->
                Flux.concat(Flux.just(claimed), Flux.defer(() -> claimUpTo(claim, remaining - 1))));
  }
}
