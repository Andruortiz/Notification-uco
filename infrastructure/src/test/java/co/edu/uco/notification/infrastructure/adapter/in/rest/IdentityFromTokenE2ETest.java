package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
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
      "notification.catalog.refresh-interval-ms=500"
    })
@Testcontainers
class IdentityFromTokenE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

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

  private String accept(final String externalId, final String bearer, final String tenantHeader) {
    final Map<?, ?> accepted =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", bearer)
            .header("X-Tenant-Id", tenantHeader)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(notificationBody(externalId))
            .exchange()
            .expectStatus()
            .isEqualTo(202)
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    return (String) accepted.get("notificationId");
  }

  private WebTestClient.ResponseSpec detail(
      final String notificationId, final String bearer, final String tenantHeader) {
    return webTestClient
        .get()
        .uri("/notifications/detail?notificationId={id}", notificationId)
        .header("Authorization", bearer)
        .header("X-Tenant-Id", tenantHeader)
        .exchange();
  }

  @Test
  void theTenantHeaderNeverChangesWhoOwnsOrCanReadANotification() {
    final String ownedByA = accept("identity-e2e-a", TestTokens.bearer("tenant-a"), "tenant-b");

    detail(ownedByA, TestTokens.bearer("tenant-a"), "tenant-b").expectStatus().isOk();
    detail(ownedByA, TestTokens.bearer("tenant-b"), "tenant-a").expectStatus().isNotFound();
    detail("missing-id", TestTokens.bearer("tenant-b"), "tenant-a").expectStatus().isNotFound();
  }

  @Test
  void aSignedTokenWithoutTenantIsRejectedWithTheDedicatedCodeAndNothingIsPersisted() {
    final long before = mongoTemplate.count(new Query(), "notifications").block();
    final String withoutTenant =
        TestTokens.sign(
            Jwts.builder()
                .subject("client-1")
                .claim("role", "ADMINISTRADOR")
                .expiration(new Date(System.currentTimeMillis() + 60_000)));

    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", "Bearer " + withoutTenant)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(notificationBody("identity-e2e-no-tenant"))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-2004");

    final long after = mongoTemplate.count(new Query(), "notifications").block();
    assertEquals(before, after);
  }

  @Test
  void aTokenSignedWithTheLegacySharedSecretIsRejected() {
    final String legacy =
        Jwts.builder()
            .subject("client-1")
            .claim("tenantId", "tenant-a")
            .claim("role", "ADMINISTRADOR")
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(
                Keys.hmacShaKeyFor(
                    "notification-uco-dev-only-secret-change-me-0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8)))
            .compact();

    webTestClient
        .get()
        .uri("/notifications/detail?notificationId=any")
        .header("Authorization", "Bearer " + legacy)
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-2001");
  }
}
