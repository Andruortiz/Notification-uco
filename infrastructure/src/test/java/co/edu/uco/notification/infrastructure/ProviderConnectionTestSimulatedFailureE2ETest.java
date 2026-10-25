package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
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
      "notification.provider.simulated.result=PERMANENT_FAILURE",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class ProviderConnectionTestSimulatedFailureE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  private static void awaitCatalog(final WebTestClient client) {
    final Instant deadline = Instant.now().plusSeconds(30);
    while (true) {
      final int items =
          client
              .get()
              .uri("/channels")
              .header("Authorization", TestTokens.bearer("tenant-1", Role.CLIENTE))
              .exchange()
              .expectBody(new ParameterizedTypeReference<Map<String, List<Object>>>() {})
              .returnResult()
              .getResponseBody()
              .get("items")
              .size();
      if (items == 3) {
        return;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("the channel catalog was never loaded");
      }
      try {
        Thread.sleep(100);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Test
  void simulatedReportsItsConfiguredFailureInUnderOneSecondOnEveryChannel() {
    final WebTestClient client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    awaitCatalog(client);

    for (final String channel : List.of("EMAIL", "SMS", "PUSH")) {
      final Instant startedAt = Instant.now();
      final Map<String, Object> body =
          client
              .post()
              .uri("/provider-connection-tests")
              .header("Authorization", TestTokens.bearer("tenant-1", Role.ADMINISTRADOR))
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(Map.of("channel", channel, "providerId", "simulated"))
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();
      final Duration elapsed = Duration.between(startedAt, Instant.now());

      assertEquals("FAILED", body.get("status"), channel);
      assertEquals("SIMULATED_FAILURE", body.get("errorCode"), channel);
      assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, channel + " took " + elapsed);
    }
  }
}
