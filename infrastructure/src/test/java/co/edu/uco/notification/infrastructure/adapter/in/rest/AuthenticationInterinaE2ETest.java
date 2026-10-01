package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticationWebFilter;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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
      "notification.catalog.refresh-interval-ms=500"
    })
@Testcontainers
class AuthenticationInterinaE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

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

  private static Map<String, Object> notificationBody(final String externalId) {
    return Map.of(
        "externalId", externalId,
        "channelType", "EMAIL",
        "recipientId", "recipient-1",
        "recipientAddress", "alice@example.com",
        "subject", "Subject",
        "body", "Body",
        "priority", "NORMAL");
  }

  @Test
  void requestWithoutATokenIsRejectedBeforeReachingAnyUseCase() {
    webTestClient
        .post()
        .uri("/notifications")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(notificationBody("us1-no-token"))
        .exchange()
        .expectStatus()
        .isUnauthorized();

    webTestClient
        .get()
        .uri("/notifications/{id}", "00000000-0000-0000-0000-000000000000")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void tenantIdAlwaysComesFromTheTokenNeverFromAHeader() {
    final String externalId = "us2-tenant-from-token";

    final Map<?, ?> accepted =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer("tenant-a"))
            .header("X-Tenant-Id", "tenant-b")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(notificationBody(externalId))
            .exchange()
            .expectStatus()
            .isEqualTo(202)
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    final String notificationId = (String) accepted.get("notificationId");

    webTestClient
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("Authorization", TestTokens.bearer("tenant-a"))
        .exchange()
        .expectStatus()
        .isOk();

    webTestClient
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("Authorization", TestTokens.bearer("tenant-b"))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void searchRequiresOperadorRoleWhileIndividualStatusOnlyRequiresCliente() {
    final Map<?, ?> accepted =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer("tenant-role-check", Role.CLIENTE))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(notificationBody("us3-role-check"))
            .exchange()
            .expectStatus()
            .isEqualTo(202)
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    final String notificationId = (String) accepted.get("notificationId");

    webTestClient
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("Authorization", TestTokens.bearer("tenant-role-check", Role.CLIENTE))
        .exchange()
        .expectStatus()
        .isOk();

    webTestClient
        .get()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-role-check", Role.CLIENTE))
        .exchange()
        .expectStatus()
        .isForbidden();

    webTestClient
        .get()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-role-check", Role.OPERADOR))
        .exchange()
        .expectStatus()
        .isOk();

    webTestClient
        .get()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-role-check", Role.ADMINISTRADOR))
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void subscribeAcceptsTheTokenAsAQueryParamBecauseEventSourceCannotSendHeaders() {
    final String token = TestTokens.bearer("tenant-sse").substring("Bearer ".length());

    webTestClient
        .get()
        .uri("/notifications:subscribe?access_token=" + token)
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void noRejectionLogEverContainsTheRawTokenOrTheSigningSecret() {
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      final String token = TestTokens.bearer("tenant-log-check");

      webTestClient.post().uri("/notifications").exchange().expectStatus().isUnauthorized();

      webTestClient
          .get()
          .uri("/notifications")
          .header("Authorization", TestTokens.bearer("tenant-log-check", Role.CLIENTE))
          .exchange()
          .expectStatus()
          .isForbidden();

      final List<String> lines =
          logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertTrue(lines.stream().anyMatch(line -> line.contains("MISSING_TOKEN")));
      assertTrue(lines.stream().anyMatch(line -> line.contains("INSUFFICIENT_ROLE")));
      assertFalse(lines.stream().anyMatch(line -> line.contains(token)));
      assertFalse(lines.stream().anyMatch(line -> line.contains(TestTokens.SECRET)));
    } finally {
      release(logs);
    }
  }

  private ListAppender<ILoggingEvent> captureLogs() {
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(AuthenticationWebFilter.class)).addAppender(appender);
    return appender;
  }

  private static void release(final ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(AuthenticationWebFilter.class)).detachAppender(appender);
  }
}
