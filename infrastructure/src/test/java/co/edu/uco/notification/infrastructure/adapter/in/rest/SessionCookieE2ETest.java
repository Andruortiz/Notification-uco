package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.support.StubSecurityPlatform;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "security.enabled=true",
      "security.pep.enforcement.enabled=true",
      "security.pep.enforcement.allow-insecure-http=true",
      "security.pep.enforcement.application-name=notification-service",
      "security.pep.enforcement.environment=test",
      "security.pep.enforcement.application-credential=test-credential",
      "notification.auth.session.timeout-ms=1500"
    })
@Testcontainers
class SessionCookieE2ETest {

  private static final String COOKIE = StubSecurityPlatform.COOKIE_NAME;
  private static final String TENANT_A = "tenant-cookie-a";
  private static final String TENANT_B = "tenant-cookie-b";

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

  @Autowired private NotificationRepository notificationRepository;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    PLATFORM.reset();
    PLATFORM.withSession("cookie-a", TENANT_A).withSession("cookie-b", TENANT_B);
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  private Notification persistAccepted(final String tenantId, final String recipientId) {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of(tenantId),
                ExternalId.of("cookie-" + UUID.randomUUID()),
                ChannelType.of("EMAIL"),
                RecipientId.of(recipientId),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    notification.pullEvents();
    return notificationRepository.save(notification).block();
  }

  private WebTestClient.ResponseSpec detailWithCookie(final String cookie, final Object id) {
    return client
        .get()
        .uri("/notifications/detail?notificationId={id}", id)
        .cookie(COOKIE, cookie)
        .exchange();
  }

  private Flux<ServerSentEvent<NotificationLiveUpdateResponse>> subscribeWithCookie(
      final String cookie) {
    return client
        .get()
        .uri("/notifications/stream")
        .cookie(COOKIE, cookie)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(
            new ParameterizedTypeReference<ServerSentEvent<NotificationLiveUpdateResponse>>() {})
        .getResponseBody()
        .filter(event -> event.data() != null);
  }

  @Test
  void anOperatorWithOnlyTheSessionCookieSendsAndReadsBackHisNotification() {
    final Map<String, Object> body =
        Map.of(
            "externalId", "cookie-send-" + UUID.randomUUID(),
            "channelType", "EMAIL",
            "recipientId", "recipient-1",
            "recipientAddress", "alice@example.com",
            "subject", "Subject",
            "body", "Body",
            "priority", "NORMAL");
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
    Map<String, Object> accepted = null;
    while (accepted == null && Instant.now().isBefore(deadline)) {
      final var result =
          client
              .post()
              .uri("/notifications")
              .cookie(COOKIE, "cookie-a")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(body)
              .exchange()
              .returnResult(new ParameterizedTypeReference<Map<String, Object>>() {});
      if (result.getStatus().value() == 202) {
        accepted = result.getResponseBody().blockFirst();
      } else {
        pause(Duration.ofSeconds(1));
      }
    }
    assertNotNull(accepted, "the notification was never accepted with the session cookie");

    detailWithCookie("cookie-a", accepted.get("notificationId")).expectStatus().isOk();
    detailWithCookie("cookie-b", accepted.get("notificationId")).expectStatus().isNotFound();
  }

  @Test
  void aSessionOfOneTenantNeverSeesTheNotificationsOfAnother() {
    final String recipient = "recipient-" + UUID.randomUUID();
    final Notification ofA = persistAccepted(TENANT_A, recipient);
    final String idOfA = ofA.notificationId().value().toString();

    detailWithCookie("cookie-a", idOfA).expectStatus().isOk();
    detailWithCookie("cookie-b", idOfA).expectStatus().isNotFound();
    detailWithCookie("cookie-b", UUID.randomUUID()).expectStatus().isNotFound();

    final String listedForA =
        client
            .get()
            .uri("/notifications?recipientId={recipient}", recipient)
            .cookie(COOKIE, "cookie-a")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    final String listedForB =
        client
            .get()
            .uri("/notifications?recipientId={recipient}", recipient)
            .cookie(COOKIE, "cookie-b")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertTrue(listedForA.contains(idOfA));
    assertFalse(listedForB.contains(idOfA));
  }

  @Test
  void aCookieSubscriptionReceivesOnlyTheEventsOfItsOwnTenant() {
    final String idOfA =
        persistAccepted(TENANT_A, "recipient-" + UUID.randomUUID())
            .notificationId()
            .value()
            .toString();
    final String idOfB =
        persistAccepted(TENANT_B, "recipient-" + UUID.randomUUID())
            .notificationId()
            .value()
            .toString();

    StepVerifier.create(
            subscribeWithCookie("cookie-a")
                .filter(event -> idOfA.equals(event.data().notification().notificationId()))
                .take(1))
        .expectNextCount(1)
        .expectComplete()
        .verify(Duration.ofSeconds(20));

    StepVerifier.create(
            subscribeWithCookie("cookie-b")
                .take(Duration.ofSeconds(3))
                .map(event -> event.data().notification().notificationId())
                .collectList())
        .assertNext(
            ids -> {
              assertTrue(ids.contains(idOfB));
              assertFalse(ids.contains(idOfA));
            })
        .verifyComplete();
  }

  @Test
  void theStreamRejectsMissingCredentialsTicketsAndInvalidSessionsAndSignalsPlatformOutage() {
    client.get().uri("/notifications/stream").exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri("/notifications/stream?ticket=any-ticket-value")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    assertEquals(0, PLATFORM.sessionCalls());

    client
        .get()
        .uri("/notifications/stream")
        .cookie(COOKIE, "never-issued")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    client
        .post()
        .uri("/notifications/stream/tickets")
        .cookie(COOKIE, "cookie-a")
        .exchange()
        .expectStatus()
        .is4xxClientError();

    PLATFORM.failSessionWith(500);
    client
        .get()
        .uri("/notifications/stream")
        .cookie(COOKIE, "cookie-a")
        .exchange()
        .expectStatus()
        .isEqualTo(503);
  }

  @Test
  void aRequestWithoutCredentialIsRejectedBeforeTheSecurityPlatformIsConsulted() {
    client
        .get()
        .uri("/notifications")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-2001");

    assertEquals(0, PLATFORM.sessionCalls());
    assertEquals(0, PLATFORM.decisionCalls());
  }

  @Test
  void anUnknownOrRevokedSessionIsRejectedWithUnauthorizedWithinTwoSeconds() {
    final Instant start = Instant.now();
    client
        .get()
        .uri("/notifications")
        .cookie(COOKIE, "never-issued")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(2)) < 0);

    client.get().uri("/notifications").cookie(COOKIE, "cookie-a").exchange().expectStatus().isOk();
    PLATFORM.revoke("cookie-a");
    final Instant revokedAt = Instant.now();
    client
        .get()
        .uri("/notifications")
        .cookie(COOKIE, "cookie-a")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    assertTrue(Duration.between(revokedAt, Instant.now()).compareTo(Duration.ofSeconds(2)) < 0);
  }

  @Test
  void aBrokenOrSlowSessionPlatformAnswers503WithinFiveSeconds() {
    PLATFORM.failSessionWith(500);
    final Instant failedAt = Instant.now();
    client
        .get()
        .uri("/notifications")
        .cookie(COOKIE, "cookie-a")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-2003");
    assertTrue(Duration.between(failedAt, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0);

    PLATFORM.reset();
    PLATFORM.withSession("cookie-a", TENANT_A);
    PLATFORM.delaySessionBy(Duration.ofSeconds(10));
    final Instant slowAt = Instant.now();
    client
        .get()
        .uri("/notifications")
        .cookie(COOKIE, "cookie-a")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-2003");
    assertTrue(Duration.between(slowAt, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0);
  }

  @Test
  void aValidBearerWinsOverTheCookieOfAnotherTenantAndAnInvalidBearerDoesNotFallBack() {
    final Notification ofA = persistAccepted(TENANT_A, "recipient-" + UUID.randomUUID());
    final String idOfA = ofA.notificationId().value().toString();

    client
        .get()
        .uri("/notifications/detail?notificationId={id}", idOfA)
        .header("Authorization", TestTokens.bearer(TENANT_A))
        .cookie(COOKIE, "cookie-b")
        .exchange()
        .expectStatus()
        .isOk();
    detailWithCookie("cookie-b", idOfA).expectStatus().isNotFound();

    final int sessionCallsBefore = PLATFORM.sessionCalls();
    client
        .get()
        .uri("/notifications/detail?notificationId={id}", idOfA)
        .header("Authorization", "Bearer not-a-jwt")
        .cookie(COOKIE, "cookie-a")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    assertEquals(sessionCallsBefore, PLATFORM.sessionCalls());
  }

  private static void pause(final Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
