package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "MINIO_ACCESS_KEY=unreachable",
      "MINIO_SECRET_KEY=unreachable-secret",
      "notification.attachments.scan.timeout=2s"
    })
@Testcontainers
class AttachmentHealthIndicatorsTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @DynamicPropertySource
  static void unreachableDependencies(final DynamicPropertyRegistry registry) throws IOException {
    final int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    registry.add("notification.attachments.clamav.host", () -> "localhost");
    registry.add("notification.attachments.clamav.port", () -> closedPort);
    registry.add(
        "notification.attachments.storage.endpoint", () -> "http://localhost:" + closedPort);
  }

  @LocalManagementPort private int port;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  private Map<String, Object> health(final String path, final int expectedStatus) {
    return webTestClient
        .get()
        .uri(path)
        .exchange()
        .expectStatus()
        .isEqualTo(expectedStatus)
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  @Test
  void aDownAntivirusOrStorageDoesNotTakeTheServiceOutOfReadiness() {
    final Map<String, Object> readiness = health("/actuator/health/readiness", 200);

    assertEquals("UP", readiness.get("status"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void theHealthEndpointReportsTheAntivirusAndTheStorageWithoutDetails() {
    final Map<String, Object> health = health("/actuator/health", 503);
    final Map<String, Object> components = (Map<String, Object>) health.get("components");

    assertEquals("DOWN", ((Map<String, Object>) components.get("clamav")).get("status"));
    assertEquals("DOWN", ((Map<String, Object>) components.get("attachmentStorage")).get("status"));
    assertEquals("UP", ((Map<String, Object>) components.get("mongo")).get("status"));
    assertFalse(health.toString().contains("localhost"), health.toString());
  }

  @Test
  void livenessIsUp() {
    assertEquals("UP", health("/actuator/health/liveness", 200).get("status"));
  }
}
