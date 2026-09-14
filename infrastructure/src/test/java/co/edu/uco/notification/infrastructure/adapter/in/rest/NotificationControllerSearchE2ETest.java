package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class NotificationControllerSearchE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private NotificationRepository notificationRepository;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
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

  private void persist(
      final String tenantId,
      final String externalId,
      final String recipientId,
      final NotificationStatus status,
      final Instant acceptedAt) {
    persist(tenantId, externalId, recipientId, status, acceptedAt, List.of());
  }

  private void persist(
      final String tenantId,
      final String externalId,
      final String recipientId,
      final NotificationStatus status,
      final Instant acceptedAt,
      final List<DeliveryAttempt> deliveryAttempts) {
    final Notification notification =
        Notification.reconstitute(
            NotificationId.newId(),
            new NotificationRouting(
                TenantId.of(tenantId),
                ExternalId.of(externalId),
                ChannelType.of("EMAIL"),
                RecipientId.of(recipientId),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            status,
            new NotificationMetadata(acceptedAt, null),
            deliveryAttempts);
    notificationRepository.save(notification).block();
  }

  @Test
  void searchCombinesFiltersAndReturnsAPaginatedHistoryOverHttp() {
    persist(
        "tenant-1",
        "order-1",
        "recipient-a",
        NotificationStatus.FAILED,
        Instant.parse("2026-06-10T00:00:00Z"));
    persist(
        "tenant-1",
        "order-2",
        "recipient-a",
        NotificationStatus.PENDING,
        Instant.parse("2026-06-15T00:00:00Z"));
    persist(
        "tenant-1",
        "order-3",
        "recipient-b",
        NotificationStatus.FAILED,
        Instant.parse("2026-06-20T00:00:00Z"));
    persist(
        "tenant-2",
        "order-4",
        "recipient-a",
        NotificationStatus.FAILED,
        Instant.parse("2026-06-12T00:00:00Z"));

    webTestClient
        .get()
        .uri(
            "/notifications?recipientId=recipient-a&status=FAILED&from=2026-06-01T00:00:00Z&to=2026-06-30T00:00:00Z")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].externalId")
        .isEqualTo("order-1")
        .jsonPath("$.items[0].status")
        .isEqualTo("FAILED")
        .jsonPath("$.items[0].deliveryAttempts")
        .isArray()
        .jsonPath("$.limit")
        .isEqualTo(50)
        .jsonPath("$.hasNext")
        .isEqualTo(false);
  }

  @Test
  void searchOrdersMostRecentFirstAndPaginates() {
    persist(
        "tenant-1",
        "order-1",
        "recipient-a",
        NotificationStatus.PENDING,
        Instant.parse("2026-01-01T00:00:00Z"));
    persist(
        "tenant-1",
        "order-2",
        "recipient-a",
        NotificationStatus.PENDING,
        Instant.parse("2026-03-01T00:00:00Z"));
    persist(
        "tenant-1",
        "order-3",
        "recipient-a",
        NotificationStatus.PENDING,
        Instant.parse("2026-02-01T00:00:00Z"));

    webTestClient
        .get()
        .uri("/notifications?limit=1")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].externalId")
        .isEqualTo("order-2")
        .jsonPath("$.hasNext")
        .isEqualTo(true);

    webTestClient
        .get()
        .uri("/notifications?limit=1&offset=1")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].externalId")
        .isEqualTo("order-3")
        .jsonPath("$.hasNext")
        .isEqualTo(true);
  }

  @Test
  void searchReturnsAnEmptyPageWhenNothingMatches() {
    webTestClient
        .get()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0)
        .jsonPath("$.hasNext")
        .isEqualTo(false);
  }

  @Test
  void searchRejectsAnInvalidDateRange() {
    webTestClient
        .get()
        .uri("/notifications?from=2026-09-20T00:00:00Z&to=2026-09-01T00:00:00Z")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void searchRejectsAnOutOfRangeLimit() {
    webTestClient
        .get()
        .uri("/notifications?limit=500")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void searchIncludesTheFullDeliveryAttemptHistoryNotJustTheLastAttempt() {
    final List<DeliveryAttempt> attempts =
        List.of(
            DeliveryAttempt.of(
                Instant.parse("2026-06-10T00:00:00Z"),
                AttemptResult.RECOVERABLE_FAILURE,
                AttemptOrigin.AUTOMATIC,
                ProviderId.of("simulated")),
            DeliveryAttempt.of(
                Instant.parse("2026-06-10T00:05:00Z"),
                AttemptResult.ACCEPTED,
                AttemptOrigin.AUTOMATIC,
                ProviderId.of("simulated")));
    persist(
        "tenant-1",
        "order-1",
        "recipient-a",
        NotificationStatus.DELIVERED,
        Instant.parse("2026-06-10T00:00:00Z"),
        attempts);

    webTestClient
        .get()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].deliveryAttempts.length()")
        .isEqualTo(2)
        .jsonPath("$.items[0].deliveryAttempts[0].result")
        .isEqualTo("RECOVERABLE_FAILURE")
        .jsonPath("$.items[0].deliveryAttempts[1].result")
        .isEqualTo("ACCEPTED")
        .jsonPath("$.items[0].deliveryAttempts[1].providerId")
        .isEqualTo("simulated");
  }

  @Test
  void searchNeverReturnsAnotherTenantsNotifications() {
    persist("tenant-1", "order-1", "recipient-a", NotificationStatus.PENDING, Instant.now());
    persist("tenant-2", "order-2", "recipient-a", NotificationStatus.PENDING, Instant.now());

    webTestClient
        .get()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].externalId")
        .isEqualTo("order-1");
  }
}
