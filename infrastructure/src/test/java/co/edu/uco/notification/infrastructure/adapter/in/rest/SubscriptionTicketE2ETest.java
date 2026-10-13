package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.auth.subscription-ticket.ttl-seconds=3"
    })
@Testcontainers
class SubscriptionTicketE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private NotificationRepository notificationRepository;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    mongoTemplate.dropCollection(NotificationDocument.class).block();
    mongoTemplate
        .indexOps(NotificationDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(Document.parse("{'tenantId': 1, 'externalId': 1}"))
                .unique()
                .named("tenant_external_unique"))
        .block();
  }

  private void persistAccepted(final String tenantId, final String externalId) {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of(tenantId),
                ExternalId.of(externalId),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    notification.pullEvents();
    notificationRepository.save(notification).block();
  }

  private String issueTicket(final String tenantId) {
    final Map<?, ?> body =
        webTestClient
            .post()
            .uri("/notifications:subscribeTicket")
            .header("Authorization", TestTokens.bearer(tenantId, Role.CLIENTE))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    assertEquals(3, body.get("expiresInSeconds"));
    final String ticket = (String) body.get("ticket");
    assertEquals(43, ticket.length());
    return ticket;
  }

  private Flux<ServerSentEvent<NotificationLiveUpdateResponse>> subscribeWithTicket(
      final String ticket) {
    return webTestClient
        .get()
        .uri("/notifications:subscribe?ticket=" + ticket)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(
            new ParameterizedTypeReference<ServerSentEvent<NotificationLiveUpdateResponse>>() {})
        .getResponseBody()
        .filter(event -> event.data() != null);
  }

  @Test
  void aValidTicketOpensTheFlowAndDeliversTheInitialSnapshot() {
    persistAccepted("tenant-ticket-a", "ticket-order-1");
    final String ticket = issueTicket("tenant-ticket-a");

    StepVerifier.create(subscribeWithTicket(ticket))
        .assertNext(
            event -> {
              assertEquals("UPSERT", event.data().action());
              assertEquals("ticket-order-1", event.data().notification().externalId());
            })
        .thenCancel()
        .verify(Duration.ofSeconds(20));
  }

  @Test
  void theSnapshotOfATicketNeverIncludesAnotherTenant() {
    persistAccepted("tenant-ticket-a", "ticket-order-a");
    persistAccepted("tenant-ticket-b", "ticket-order-b");
    final String ticket = issueTicket("tenant-ticket-a");

    StepVerifier.create(subscribeWithTicket(ticket).take(Duration.ofSeconds(3)))
        .assertNext(
            event -> assertEquals("ticket-order-a", event.data().notification().externalId()))
        .verifyComplete();
  }

  @Test
  void theSameTicketUsedASecondTimeIsRejectedWithUnauthorized() {
    persistAccepted("tenant-ticket-a", "ticket-order-2");
    final String ticket = issueTicket("tenant-ticket-a");

    StepVerifier.create(subscribeWithTicket(ticket))
        .expectNextCount(1)
        .thenCancel()
        .verify(Duration.ofSeconds(20));

    webTestClient
        .get()
        .uri("/notifications:subscribe?ticket=" + ticket)
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void anExpiredTicketIsRejectedWithUnauthorized() throws InterruptedException {
    final String ticket = issueTicket("tenant-ticket-a");

    Thread.sleep(Duration.ofSeconds(4).toMillis());

    webTestClient
        .get()
        .uri("/notifications:subscribe?ticket=" + ticket)
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void anUnknownOrMissingTicketIsRejectedWithUnauthorized() {
    webTestClient
        .get()
        .uri("/notifications:subscribe?ticket=never-issued")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    webTestClient.get().uri("/notifications:subscribe").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void theAccessTokenQueryParamIsNoLongerAccepted() {
    final String jwt = TestTokens.bearer("tenant-ticket-a").substring("Bearer ".length());

    webTestClient
        .get()
        .uri("/notifications:subscribe?access_token=" + jwt)
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void issuingATicketRequiresAnAuthenticatedPrincipal() {
    webTestClient
        .post()
        .uri("/notifications:subscribeTicket")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void theClearTicketNeverAppearsInTheLogs() {
    final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    root.addAppender(logs);
    try {
      persistAccepted("tenant-ticket-a", "ticket-order-3");
      final String ticket = issueTicket("tenant-ticket-a");

      StepVerifier.create(subscribeWithTicket(ticket))
          .expectNextCount(1)
          .thenCancel()
          .verify(Duration.ofSeconds(20));
      webTestClient
          .get()
          .uri("/notifications:subscribe?ticket=" + ticket)
          .exchange()
          .expectStatus()
          .isUnauthorized();

      final List<String> lines = logs.list.stream().map(LogLines::render).toList();
      assertFalse(lines.isEmpty());
      assertTrue(
          lines.stream().anyMatch(line -> line.contains("Request rejected by authentication")));
      assertTrue(lines.stream().noneMatch(line -> line.contains(ticket)));
    } finally {
      root.detachAppender(logs);
    }
  }
}
