package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.repository.NotificationBatchRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class NotificationBatchMongoAdapter implements NotificationBatchRepository {

  private final ReactiveMongoTemplate mongoTemplate;
  private final Clock clock;

  public NotificationBatchMongoAdapter(
      final ReactiveMongoTemplate mongoTemplate, final Clock clock) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<BatchAcceptedResult> save(final BatchAcceptedResult result, final TenantId tenantId) {
    Preconditions.requireNonNull(result, "result must not be null");
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    return mongoTemplate
        .save(NotificationBatchDocumentMapper.toDocument(tenantId, result, clock.instant()))
        .thenReturn(result)
        .onErrorResume(
            DuplicateKeyException.class, ex -> findByTenantAndBatchId(tenantId, result.batchId()));
  }

  @Override
  public Mono<BatchAcceptedResult> findByTenantAndBatchId(
      final TenantId tenantId, final BatchId batchId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(batchId, "batchId must not be null");
    return mongoTemplate
        .findOne(
            Query.query(
                Criteria.where("tenantId").is(tenantId.value()).and("batchId").is(batchId.value())),
            NotificationBatchDocument.class)
        .map(NotificationBatchDocumentMapper::toResult);
  }
}
