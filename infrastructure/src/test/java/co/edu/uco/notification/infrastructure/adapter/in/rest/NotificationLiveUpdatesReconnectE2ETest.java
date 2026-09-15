package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument;
import java.time.Duration;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class NotificationLiveUpdatesReconnectE2ETest {

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

  private Notification persistAccepted(final String externalId) {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of(externalId),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    notification.pullEvents();
    return notificationRepository.save(notification).block();
  }

  private Flux<ServerSentEvent<NotificationLiveUpdateResponse>> subscribe() {
    return webTestClient
        .get()
        .uri("/notifications:subscribe")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(
            new ParameterizedTypeReference<ServerSentEvent<NotificationLiveUpdateResponse>>() {})
        .getResponseBody()
        .filter(event -> event.data() != null);
  }

  @Test
  void reconnectingAfterADisconnectionResyncsToTheCurrentStateIncludingMissedChanges() {
    final Notification notification = persistAccepted("order-reconnect-1");

    StepVerifier.create(subscribe())
        .assertNext(event -> assertEquals("PENDING", event.data().notification().status()))
        .thenCancel()
        .verify(Duration.ofSeconds(20));

    final Notification reloaded =
        notificationRepository.findById(notification.notificationId()).block();
    reloaded.markQueued();
    reloaded.markDelivered(AttemptOrigin.AUTOMATIC, ProviderId.of("simulated"));
    reloaded.pullEvents();
    notificationRepository.save(reloaded).block();

    StepVerifier.create(subscribe())
        .assertNext(
            event -> {
              assertEquals("UPSERT", event.data().action());
              assertEquals("DELIVERED", event.data().notification().status());
            })
        .thenCancel()
        .verify(Duration.ofSeconds(20));
  }
}
