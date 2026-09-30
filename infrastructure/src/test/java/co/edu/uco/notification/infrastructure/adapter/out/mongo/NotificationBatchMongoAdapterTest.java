package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemResult;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class NotificationBatchMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private static final TenantId TENANT_A = TenantId.of("tenant-a");
  private static final TenantId TENANT_B = TenantId.of("tenant-b");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private NotificationBatchMongoAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new NotificationBatchMongoAdapter(mongoTemplate);
    mongoTemplate.dropCollection(NotificationBatchDocument.class).block();
    mongoTemplate
        .indexOps(NotificationBatchDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(Document.parse("{'tenantId': 1, 'batchId': 1}"))
                .unique()
                .named("tenant_batch_unique"))
        .block();
  }

  private static BatchAcceptedResult aResult(final String batchId) {
    return new BatchAcceptedResult(
        BatchId.of(batchId),
        List.of(
            BatchItemResult.accepted(ExternalId.of("order-1"), NotificationId.newId()),
            BatchItemResult.rejected(ExternalId.of("order-2"), "Channel FAX is not available")));
  }

  @Test
  void savePersistsTheBatchRecordWithTheTenantAndAllItemResults() {
    adapter.save(aResult("batch-1"), TENANT_A).block();

    final NotificationBatchDocument stored =
        mongoTemplate
            .findAll(NotificationBatchDocument.class)
            .filter(document -> "batch-1".equals(document.batchId()))
            .blockFirst();

    assertEquals("tenant-a", stored.tenantId());
    assertEquals("batch-1", stored.batchId());
    assertEquals(2, stored.results().size());
    assertEquals("order-1", stored.results().get(0).externalId());
    assertEquals("ACCEPTED", stored.results().get(0).outcome());
    assertEquals("order-2", stored.results().get(1).externalId());
    assertEquals("REJECTED", stored.results().get(1).outcome());
    assertEquals("Channel FAX is not available", stored.results().get(1).rejectionReason());
  }

  @Test
  void theSameBatchIdInTwoTenantsIsTwoEntriesThatNeverCross() {
    adapter.save(aResult("batch-shared"), TENANT_A).block();
    adapter.save(aResult("batch-shared"), TENANT_B).block();

    final long count =
        mongoTemplate
            .count(
                new org.springframework.data.mongodb.core.query.Query(),
                NotificationBatchDocument.class)
            .block();

    assertEquals(2L, count);
    final List<NotificationBatchDocument> stored =
        mongoTemplate.findAll(NotificationBatchDocument.class).collectList().block();
    assertTrue(stored.stream().anyMatch(document -> "tenant-a".equals(document.tenantId())));
    assertTrue(stored.stream().anyMatch(document -> "tenant-b".equals(document.tenantId())));
  }
}
