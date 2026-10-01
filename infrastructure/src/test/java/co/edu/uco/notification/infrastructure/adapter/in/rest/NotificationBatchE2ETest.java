package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500"
    })
@Testcontainers
class NotificationBatchE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    awaitRoute("EMAIL");
    awaitRoute("SMS");
  }

  private void awaitRoute(final String channelType) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      if (channelCatalogPort
          .findActiveRoute(ChannelType.of(channelType), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
    }
    throw new IllegalStateException("channel " + channelType + " never became active");
  }

  private static Map<String, Object> item(
      final String externalId, final String channelType, final String body) {
    return Map.of(
        "externalId", externalId,
        "channelType", channelType,
        "recipientId", "recipient-1",
        "recipientAddress", "alice@example.com",
        "subject", "Subject",
        "body", body,
        "priority", "NORMAL");
  }

  private Map<String, Object> postBatch(final String tenant, final Object body) {
    return webTestClient
        .post()
        .uri("/notifications:sendBatch")
        .header("Authorization", TestTokens.bearer(tenant))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isEqualTo(202)
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> resultsOf(final Map<String, Object> response) {
    return (List<Map<String, Object>>) response.get("results");
  }

  private Map<String, Object> status(final String tenant, final String notificationId) {
    return webTestClient
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("Authorization", TestTokens.bearer(tenant))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private Map<String, Object> awaitStatus(
      final String tenant, final String notificationId, final String expectedStatus) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
    Map<String, Object> current = status(tenant, notificationId);
    while (Instant.now().isBefore(deadline)
        && !expectedStatus.equals(String.valueOf(current.get("status")))) {
      current = status(tenant, notificationId);
    }
    return current;
  }

  private long storedNotificationsForTenant(final String tenant) {
    return mongoTemplate
        .getCollection("notifications")
        .flatMap(
            collection -> Mono.from(collection.countDocuments(new Document("tenantId", tenant))))
        .block();
  }

  @Test
  void sendBatchAcceptsSeveralValidItemsAndEachOneReachesDeliveredThroughTheNormalFlow() {
    final String tenant = "tenant-batch-happy";
    final Map<String, Object> response =
        postBatch(
            tenant,
            Map.of(
                "batchId",
                "batch-happy-1",
                "items",
                List.of(
                    item("happy-1", "EMAIL", "Body"),
                    item("happy-2", "EMAIL", "Body"),
                    item("happy-3", "EMAIL", "Body"))));

    assertEquals("batch-happy-1", response.get("batchId"));
    final List<Map<String, Object>> results = resultsOf(response);
    assertEquals(3, results.size());
    assertEquals("happy-1", results.get(0).get("externalId"));
    assertEquals("happy-2", results.get(1).get("externalId"));
    assertEquals("happy-3", results.get(2).get("externalId"));
    results.forEach(result -> assertEquals("ACCEPTED", result.get("outcome")));

    results.forEach(
        result -> {
          final String notificationId = result.get("notificationId").toString();
          assertEquals("DELIVERED", awaitStatus(tenant, notificationId, "DELIVERED").get("status"));
        });
  }

  @Test
  void sendBatchAcceptsValidItemsAndRejectsInvalidOnesWithoutBlockingTheOthers() {
    final String tenant = "tenant-batch-mixed";
    final String repeatedExternalId = "mixed-duplicate";
    final Map<String, Object> firstAccept =
        postBatch(tenant, Map.of("items", List.of(item(repeatedExternalId, "EMAIL", "Body"))));
    final String originalNotificationId =
        resultsOf(firstAccept).get(0).get("notificationId").toString();

    final String tooLongSmsBody = "a".repeat(161);
    final Map<String, Object> response =
        postBatch(
            tenant,
            Map.of(
                "items",
                List.of(
                    item("mixed-valid", "EMAIL", "Body"),
                    item("mixed-unknown-channel", "FAX", "Body"),
                    item("mixed-invalid-content", "SMS", tooLongSmsBody),
                    item(repeatedExternalId, "EMAIL", "Body"))));

    assertNotNull(response.get("batchId"));
    final List<Map<String, Object>> results = resultsOf(response);
    assertEquals(4, results.size());
    assertEquals("ACCEPTED", results.get(0).get("outcome"));
    assertEquals("REJECTED", results.get(1).get("outcome"));
    assertNotNull(results.get(1).get("rejectionReason"));
    assertEquals("REJECTED", results.get(2).get("outcome"));
    assertNotNull(results.get(2).get("rejectionReason"));
    assertEquals("DUPLICATE", results.get(3).get("outcome"));
    assertEquals(originalNotificationId, results.get(3).get("notificationId"));
  }

  @Test
  void twoTenantsWithTheSameExternalIdsGetIsolatedNotificationsThatNeverCross() {
    final String tenantA = "tenant-batch-a";
    final String tenantB = "tenant-batch-b";
    final String externalId = "shared-across-tenants";

    final Map<String, Object> responseA =
        postBatch(tenantA, Map.of("items", List.of(item(externalId, "EMAIL", "Body"))));
    final Map<String, Object> responseB =
        postBatch(tenantB, Map.of("items", List.of(item(externalId, "EMAIL", "Body"))));

    assertEquals("ACCEPTED", resultsOf(responseA).get(0).get("outcome"));
    assertEquals("ACCEPTED", resultsOf(responseB).get(0).get("outcome"));
    final String notificationIdA = resultsOf(responseA).get(0).get("notificationId").toString();
    final String notificationIdB = resultsOf(responseB).get(0).get("notificationId").toString();
    assertNotEquals(notificationIdA, notificationIdB);

    webTestClient
        .get()
        .uri("/notifications/{id}", notificationIdA)
        .header("Authorization", TestTokens.bearer(tenantB))
        .exchange()
        .expectStatus()
        .isNotFound();
    webTestClient
        .get()
        .uri("/notifications/{id}", notificationIdB)
        .header("Authorization", TestTokens.bearer(tenantA))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void aStructurallyInvalidBatchCreatesNothingWhileAValidBatchStillWorks() {
    final String tenant = "tenant-batch-invalid";

    postBatch(tenant, Map.of("items", List.of(item("control-positive", "EMAIL", "Body"))));
    assertEquals(1L, storedNotificationsForTenant(tenant));

    final Map<String, Object> itemWithoutPriority =
        new HashMap<>(item("no-priority", "EMAIL", "Body"));
    itemWithoutPriority.remove("priority");

    webTestClient
        .post()
        .uri("/notifications:sendBatch")
        .header("Authorization", TestTokens.bearer(tenant))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("items", List.of(itemWithoutPriority)))
        .exchange()
        .expectStatus()
        .isBadRequest();

    assertEquals(1L, storedNotificationsForTenant(tenant));
  }
}
