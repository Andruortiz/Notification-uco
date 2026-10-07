package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class ActuatorExposureE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final List<String> NOT_APPROVED =
      List.of(
          "env",
          "beans",
          "heapdump",
          "configprops",
          "loggers",
          "threaddump",
          "mappings",
          "metrics",
          "info");

  @LocalServerPort private int apiPort;

  @LocalManagementPort private int managementPort;

  private WebTestClient api;
  private WebTestClient management;

  @BeforeEach
  void setUp() {
    api = client(apiPort);
    management = client(managementPort);
  }

  private static WebTestClient client(final int port) {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
  }

  @Test
  void theManagementPortIsADifferentPortThanTheApi() {
    assertNotEquals(apiPort, managementPort);
  }

  @Test
  void livenessAndReadinessRespondOnTheManagementPortWithoutCredentials() {
    for (final String path : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
      final EntityExchangeResult<byte[]> result =
          management.get().uri(path).exchange().expectStatus().isOk().expectBody().returnResult();
      assertEquals("UP", status(result), path);
    }
  }

  @Test
  void theAggregateHealthRespondsOnTheManagementPort() {
    final EntityExchangeResult<byte[]> result =
        management
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .value(code -> assertTrue(Set.of(200, 503).contains(code), "status " + code))
            .expectBody()
            .returnResult();
    assertTrue(Set.of("UP", "DOWN").contains(status(result)));
  }

  @Test
  void prometheusRespondsOnTheManagementPortWithoutCredentials() {
    final String body =
        management
            .get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertTrue(body.contains("jvm_memory_used_bytes"), "prometheus text must carry JVM metrics");
  }

  @Test
  void theActuatorIndexOfTheManagementPortListsOnlyHealthAndPrometheus() throws Exception {
    final EntityExchangeResult<byte[]> result =
        management.get().uri("/actuator").exchange().expectBody().returnResult();
    if (result.getStatus().is2xxSuccessful()) {
      final List<String> links = new ArrayList<>();
      MAPPER
          .readTree(result.getResponseBody())
          .get("_links")
          .fieldNames()
          .forEachRemaining(links::add);
      for (final String link : links) {
        assertTrue(
            link.equals("self") || link.startsWith("health") || link.equals("prometheus"), link);
      }
    }
  }

  @Test
  void endpointsThatAreNotApprovedServeNothingOnTheManagementPort() {
    for (final String endpoint : NOT_APPROVED) {
      final EntityExchangeResult<byte[]> result =
          management.get().uri("/actuator/" + endpoint).exchange().expectBody().returnResult();
      assertFalse(result.getStatus().is2xxSuccessful(), endpoint);
      assertEquals(404, result.getStatus().value(), endpoint);
    }
  }

  @Test
  void theApiPortServesNoActuatorRouteWithoutCredentials() {
    final List<String> paths = new ArrayList<>(List.of("health", "health/liveness", "prometheus"));
    paths.addAll(NOT_APPROVED);
    for (final String path : paths) {
      api.get().uri("/actuator/" + path).exchange().expectStatus().isUnauthorized();
    }
  }

  @Test
  void theApiPortServesNoActuatorRouteEvenWithAValidToken() {
    final List<String> paths = new ArrayList<>(List.of("health", "health/liveness", "prometheus"));
    paths.addAll(NOT_APPROVED);
    for (final String path : paths) {
      final EntityExchangeResult<byte[]> result =
          api.get()
              .uri("/actuator/" + path)
              .header("Authorization", TestTokens.bearer("tenant-a"))
              .exchange()
              .expectBody()
              .returnResult();
      assertEquals(404, result.getStatus().value(), path);
    }
  }

  @Test
  void theBusinessApiDoesNotAnswerOnTheManagementPort() {
    management
        .get()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-a"))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  private static String status(final EntityExchangeResult<byte[]> result) {
    try {
      final JsonNode node = MAPPER.readTree(result.getResponseBody());
      return node.get("status").asText();
    } catch (final java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
