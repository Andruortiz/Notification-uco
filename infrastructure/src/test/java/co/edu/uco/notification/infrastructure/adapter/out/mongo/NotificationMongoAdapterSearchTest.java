package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import java.time.Instant;
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
class NotificationMongoAdapterSearchTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private NotificationMongoAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new NotificationMongoAdapter(mongoTemplate);
    mongoTemplate.dropCollection(NotificationDocument.class).block();
    mongoTemplate
        .indexOps(NotificationDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(Document.parse("{'tenantId': 1, 'externalId': 1}"))
                .unique()
                .named("tenant_external_unique"))
        .block();
    mongoTemplate
        .indexOps(NotificationDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(Document.parse("{'tenantId': 1, 'acceptedAt': -1}"))
                .named("tenant_acceptedAt"))
        .block();
  }

  private Notification save(
      final String tenantId,
      final String externalId,
      final String recipientId,
      final String channelType,
      final Instant acceptedAt) {
    final Notification notification =
        Notification.reconstitute(
            NotificationId.newId(),
            new NotificationRouting(
                TenantId.of(tenantId),
                ExternalId.of(externalId),
                ChannelType.of(channelType),
                RecipientId.of(recipientId),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            NotificationStatus.PENDING,
            new NotificationMetadata(acceptedAt, null),
            List.of());
    return adapter.save(notification).block();
  }

  private static NotificationSearchCriteria criteriaFor(final String tenantId, final int limit) {
    return new NotificationSearchCriteria(
        TenantId.of(tenantId), null, null, null, null, null, limit, 0);
  }

  @Test
  void searchFiltersByRecipientId() {
    save("tenant-1", "order-1", "recipient-a", "EMAIL", Instant.now());
    save("tenant-1", "order-2", "recipient-b", "EMAIL", Instant.now());

    final List<Notification> results =
        adapter
            .search(
                new NotificationSearchCriteria(
                    TenantId.of("tenant-1"),
                    RecipientId.of("recipient-a"),
                    null,
                    null,
                    null,
                    null,
                    50,
                    0))
            .collectList()
            .block();

    assertEquals(1, results.size());
    assertEquals(ExternalId.of("order-1"), results.getFirst().externalId());
  }

  @Test
  void searchFiltersByChannelType() {
    save("tenant-1", "order-1", "recipient-a", "EMAIL", Instant.now());
    save("tenant-1", "order-2", "recipient-a", "SMS", Instant.now());

    final List<Notification> results =
        adapter
            .search(
                new NotificationSearchCriteria(
                    TenantId.of("tenant-1"), null, ChannelType.of("SMS"), null, null, null, 50, 0))
            .collectList()
            .block();

    assertEquals(1, results.size());
    assertEquals(ExternalId.of("order-2"), results.getFirst().externalId());
  }

  @Test
  void searchFiltersByStatus() {
    final Notification pending = save("tenant-1", "order-1", "recipient-a", "EMAIL", Instant.now());
    final Notification other = save("tenant-1", "order-2", "recipient-a", "EMAIL", Instant.now());
    other.markQueued();
    adapter.save(other).block();

    final List<Notification> results =
        adapter
            .search(
                new NotificationSearchCriteria(
                    TenantId.of("tenant-1"),
                    null,
                    null,
                    NotificationStatus.PENDING,
                    null,
                    null,
                    50,
                    0))
            .collectList()
            .block();

    assertEquals(1, results.size());
    assertEquals(pending.notificationId(), results.getFirst().notificationId());
  }

  @Test
  void searchFiltersByAcceptedAtRangeInclusive() {
    final Instant inRange = Instant.parse("2026-06-15T00:00:00Z");
    save("tenant-1", "order-1", "recipient-a", "EMAIL", inRange);
    save("tenant-1", "order-2", "recipient-a", "EMAIL", Instant.parse("2026-01-01T00:00:00Z"));

    final List<Notification> results =
        adapter
            .search(
                new NotificationSearchCriteria(
                    TenantId.of("tenant-1"),
                    null,
                    null,
                    null,
                    Instant.parse("2026-06-01T00:00:00Z"),
                    Instant.parse("2026-06-30T00:00:00Z"),
                    50,
                    0))
            .collectList()
            .block();

    assertEquals(1, results.size());
    assertEquals(ExternalId.of("order-1"), results.getFirst().externalId());
  }

  @Test
  void searchCombinesFiltersWithAnd() {
    save("tenant-1", "order-1", "recipient-a", "EMAIL", Instant.now());
    save("tenant-1", "order-2", "recipient-a", "SMS", Instant.now());
    save("tenant-1", "order-3", "recipient-b", "EMAIL", Instant.now());

    final List<Notification> results =
        adapter
            .search(
                new NotificationSearchCriteria(
                    TenantId.of("tenant-1"),
                    RecipientId.of("recipient-a"),
                    ChannelType.of("EMAIL"),
                    null,
                    null,
                    null,
                    50,
                    0))
            .collectList()
            .block();

    assertEquals(1, results.size());
    assertEquals(ExternalId.of("order-1"), results.getFirst().externalId());
  }

  @Test
  void searchOrdersByAcceptedAtDescending() {
    save("tenant-1", "order-1", "recipient-a", "EMAIL", Instant.parse("2026-01-01T00:00:00Z"));
    save("tenant-1", "order-2", "recipient-a", "EMAIL", Instant.parse("2026-03-01T00:00:00Z"));
    save("tenant-1", "order-3", "recipient-a", "EMAIL", Instant.parse("2026-02-01T00:00:00Z"));

    final List<Notification> results =
        adapter.search(criteriaFor("tenant-1", 50)).collectList().block();

    assertEquals(
        List.of(ExternalId.of("order-2"), ExternalId.of("order-3"), ExternalId.of("order-1")),
        results.stream().map(Notification::externalId).toList());
  }

  @Test
  void searchReturnsUpToLimitPlusOneToLetTheCallerDetectMorePages() {
    for (int i = 0; i < 3; i++) {
      save("tenant-1", "order-" + i, "recipient-a", "EMAIL", Instant.now());
    }

    final List<Notification> results =
        adapter.search(criteriaFor("tenant-1", 2)).collectList().block();

    assertEquals(3, results.size());
  }

  @Test
  void searchRespectsOffset() {
    save("tenant-1", "order-1", "recipient-a", "EMAIL", Instant.parse("2026-01-01T00:00:00Z"));
    save("tenant-1", "order-2", "recipient-a", "EMAIL", Instant.parse("2026-02-01T00:00:00Z"));

    final List<Notification> results =
        adapter
            .search(
                new NotificationSearchCriteria(
                    TenantId.of("tenant-1"), null, null, null, null, null, 50, 1))
            .collectList()
            .block();

    assertEquals(1, results.size());
    assertEquals(ExternalId.of("order-1"), results.getFirst().externalId());
  }

  @Test
  void searchNeverReturnsAnotherTenantsNotifications() {
    save("tenant-1", "order-1", "recipient-a", "EMAIL", Instant.now());
    save("tenant-2", "order-2", "recipient-a", "EMAIL", Instant.now());

    final List<Notification> results =
        adapter.search(criteriaFor("tenant-1", 50)).collectList().block();

    assertTrue(results.stream().allMatch(n -> n.tenantId().equals(TenantId.of("tenant-1"))));
    assertEquals(1, results.size());
  }
}
