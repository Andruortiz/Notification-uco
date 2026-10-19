package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.support.StubSecurityPlatform;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "security.enabled=true",
      "security.pep.enforcement.enabled=true",
      "security.pep.enforcement.application-name=notification-service",
      "security.pep.enforcement.environment=test",
      "security.pep.enforcement.application-credential=test-credential"
    })
@Testcontainers
class PublicRoutesLocalPepE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final StubSecurityPlatform PLATFORM = new StubSecurityPlatform();

  @DynamicPropertySource
  static void platformUrls(final DynamicPropertyRegistry registry) {
    registry.add("security.pep.enforcement.pep-url", PLATFORM::baseUrl);
    registry.add("notification.auth.session.url", PLATFORM::baseUrl);
  }

  @AfterAll
  static void stopPlatform() {
    PLATFORM.close();
  }

  @LocalServerPort private int port;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  @Test
  void theSwaggerInterfaceAnswersWithoutCredentialsInTheLocalProfile() {
    final String location =
        client
            .get()
            .uri("/swagger-ui.html")
            .exchange()
            .expectStatus()
            .is3xxRedirection()
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst("Location");

    assertTrue(location != null && location.contains("swagger-ui"), String.valueOf(location));
    client.get().uri(location).exchange().expectStatus().isOk();
  }

  @Test
  void theContractAndTheGeneratedDocumentationAnswerWithoutCredentials() {
    for (final String path :
        List.of(
            "/openapi/api-notificaciones.yaml", "/v3/api-docs", "/v3/api-docs/swagger-config")) {
      client.get().uri(path).exchange().expectStatus().isOk();
    }
  }

  @Test
  void theBusinessRoutesStayProtectedInTheLocalProfile() {
    client.get().uri("/notifications").exchange().expectStatus().isUnauthorized();
    client.post().uri("/notifications").exchange().expectStatus().isUnauthorized();
  }
}
