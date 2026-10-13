package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticationWebFilter;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
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
@Import(AuthenticationSecurityE2ETest.FailingRouteController.class)
@Testcontainers
class AuthenticationSecurityE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @RestController
  static class FailingRouteController {

    @GetMapping("/security-e2e/failure")
    Mono<String> failure() {
      return Mono.error(new IllegalStateException("simulated downstream failure"));
    }
  }

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

  private static String tokenWithoutExpiration() {
    return Jwts.builder()
        .subject("client-1")
        .claim("tenantId", "tenant-a")
        .claim("role", "ADMINISTRADOR")
        .issuedAt(new Date())
        .signWith(Keys.hmacShaKeyFor(TestTokens.SECRET.getBytes()))
        .compact();
  }

  @Test
  void aValidTokenIsAcceptedAsAPositiveControl() {
    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-a"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(notificationBody("security-e2e-positive-control"))
        .exchange()
        .expectStatus()
        .isEqualTo(202);
  }

  @Test
  void aTokenWithoutExpirationIsRejectedWithUnauthorized() {
    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", "Bearer " + tokenWithoutExpiration())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(notificationBody("security-e2e-no-expiration"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void actuatorRoutesOnTheMainPortRequireAuthenticationAndServeNothing() {
    for (final String path :
        List.of("/actuatorX", "/actuator/health", "/actuator/prometheus", "/actuator/env")) {
      webTestClient.get().uri(path).exchange().expectStatus().isUnauthorized();
      webTestClient
          .get()
          .uri(path)
          .header("Authorization", TestTokens.bearer("tenant-a"))
          .exchange()
          .expectStatus()
          .isNotFound();
    }
  }

  @Test
  void anErrorAfterAuthenticationRespondsWithAServerErrorAndIsNotLoggedAsACredentialRejection() {
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    final Logger filterLogger = (Logger) LoggerFactory.getLogger(AuthenticationWebFilter.class);
    filterLogger.addAppender(logs);
    try {
      webTestClient
          .get()
          .uri("/security-e2e/failure")
          .header("Authorization", TestTokens.bearer("tenant-a"))
          .exchange()
          .expectStatus()
          .is5xxServerError();

      final List<String> lines = logs.list.stream().map(LogLines::render).toList();
      assertTrue(
          lines.stream().noneMatch(line -> line.contains("Request rejected by authentication")));
    } finally {
      filterLogger.detachAppender(logs);
    }
  }
}
