package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.support.SampleFiles;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
      "notification.catalog.refresh-interval-ms=500",
      "notification.attachments.scan.timeout=2s"
    })
@Testcontainers
class NotificationAttachmentScannerDownE2ETest {

  private static final String TENANT = "tenant-scanner-down";
  private static final String CHANNEL = "E2E_SCANNER_DOWN";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @DynamicPropertySource
  static void unreachableAntivirus(final DynamicPropertyRegistry registry) throws IOException {
    final int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    registry.add("notification.attachments.clamav.host", () -> "localhost");
    registry.add("notification.attachments.clamav.port", () -> closedPort);
  }

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
    mongoTemplate
        .save(
            new ChannelCatalogDocument(
                CHANNEL,
                List.of("simulated"),
                NotificationAttachmentE2ETest.attachmentsSchema(10_485_760L)))
        .block();
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort.findActiveRoute(ChannelType.of(CHANNEL), TenantId.of(TENANT)).block();
      if (route != null && route.contentSchema() != null) {
        return;
      }
    }
    throw new IllegalStateException("channel did not refresh in time");
  }

  private Map<String, Object> request(final String externalId, final boolean withAttachment) {
    final Map<String, Object> body = new HashMap<>();
    body.put("externalId", externalId);
    body.put("channelType", CHANNEL);
    body.put("recipientId", "recipient-1");
    body.put("recipientAddress", "alice@example.com");
    body.put("body", "Body");
    body.put("priority", "NORMAL");
    if (withAttachment) {
      final byte[] pdf = SampleFiles.pdf("scanner-down");
      body.put(
          "attachments",
          List.of(
              Map.of(
                  "fileName",
                  "invoice.pdf",
                  "contentType",
                  "application/pdf",
                  "sizeBytes",
                  pdf.length,
                  "content",
                  Base64.getEncoder().encodeToString(pdf))));
    }
    return body;
  }

  private long stored(final String externalId) {
    return mongoTemplate
        .getCollection("notifications")
        .flatMap(
            collection ->
                Mono.from(
                    collection.countDocuments(
                        new Document("tenantId", TENANT).append("externalId", externalId))))
        .block();
  }

  @Test
  void withTheAntivirusDownAnEmbeddedAttachmentIsATemporaryErrorAndNothingIsStored() {
    final String body =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(TENANT))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request("sc014-with-attachment", true))
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertEquals(0L, stored("sc014-with-attachment"));
    assertFalse(body.contains("localhost"), body);
  }

  @Test
  void withTheAntivirusDownANotificationWithoutAttachmentsIsStillAccepted() {
    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer(TENANT))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request("sc014-control", false))
        .exchange()
        .expectStatus()
        .isAccepted();

    assertEquals(1L, stored("sc014-control"));
  }
}
