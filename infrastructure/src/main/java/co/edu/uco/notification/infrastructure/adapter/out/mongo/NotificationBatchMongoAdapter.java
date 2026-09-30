package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.repository.NotificationBatchRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class NotificationBatchMongoAdapter implements NotificationBatchRepository {

  private final ReactiveMongoTemplate mongoTemplate;

  public NotificationBatchMongoAdapter(final ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
  }

  @Override
  public Mono<Void> save(final BatchAcceptedResult result, final TenantId tenantId) {
    Preconditions.requireNonNull(result, "result must not be null");
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    return mongoTemplate
        .save(NotificationBatchDocumentMapper.toDocument(tenantId, result, Instant.now()))
        .then();
  }
}
