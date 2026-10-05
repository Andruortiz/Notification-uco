package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import reactor.test.StepVerifier;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class NotificationBatchMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private static final TenantId TENANT_A = TenantId.of("tenant-a");
  private static final TenantId TENANT_B = TenantId.of("tenant-b");
  private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private NotificationBatchMongoAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new NotificationBatchMongoAdapter(mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
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

  @Test
  void savePersistsWithTheInjectedClock() {
    adapter.save(aResult("batch-clock"), TENANT_A).block();

    final NotificationBatchDocument stored =
        mongoTemplate.findAll(NotificationBatchDocument.class).blockFirst();

    assertEquals(NOW, stored.submittedAt());
  }

  @Test
  void findByTenantAndBatchIdReturnsTheOriginalResult() {
    final BatchAcceptedResult original = aResult("batch-1");
    adapter.save(original, TENANT_A).block();

    final BatchAcceptedResult found =
        adapter.findByTenantAndBatchId(TENANT_A, BatchId.of("batch-1")).block();

    assertEquals(original, found);
    assertTrue(found.trackingSaved());
  }

  @Test
  void findByTenantAndBatchIdIsEmptyWhenTheBatchWasNeverSaved() {
    StepVerifier.create(adapter.findByTenantAndBatchId(TENANT_A, BatchId.of("unknown")))
        .verifyComplete();
  }

  @Test
  void findByTenantAndBatchIdNeverCrossesTenants() {
    final BatchAcceptedResult ofTenantA = aResult("batch-shared");
    final BatchAcceptedResult ofTenantB =
        new BatchAcceptedResult(
            BatchId.of("batch-shared"),
            List.of(BatchItemResult.rejected(ExternalId.of("order-9"), "Other tenant")));
    adapter.save(ofTenantA, TENANT_A).block();

    StepVerifier.create(adapter.findByTenantAndBatchId(TENANT_B, BatchId.of("batch-shared")))
        .verifyComplete();

    adapter.save(ofTenantB, TENANT_B).block();

    assertEquals(
        ofTenantA, adapter.findByTenantAndBatchId(TENANT_A, BatchId.of("batch-shared")).block());
    assertEquals(
        ofTenantB, adapter.findByTenantAndBatchId(TENANT_B, BatchId.of("batch-shared")).block());
  }

  @Test
  void aDuplicateSaveOfTheSameTenantAndBatchReturnsTheWinningRecord() {
    final BatchAcceptedResult winner = aResult("batch-race");
    final BatchAcceptedResult loser =
        new BatchAcceptedResult(
            BatchId.of("batch-race"),
            List.of(BatchItemResult.rejected(ExternalId.of("order-9"), "Late request")));
    adapter.save(winner, TENANT_A).block();

    final BatchAcceptedResult returned = adapter.save(loser, TENANT_A).block();

    assertEquals(winner, returned);
    assertEquals(
        1L,
        mongoTemplate
            .count(
                new org.springframework.data.mongodb.core.query.Query(),
                NotificationBatchDocument.class)
            .block());
  }
}
