package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument;
import java.time.Duration;
import java.util.List;
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
class NotificationLiveUpdatesE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private NotificationRepository notificationRepository;

  @Autowired private NotificationEventPublisherPort eventPublisherPort;

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

  private Notification persistAccepted(final String tenantId, final String externalId) {
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
    return notificationRepository.save(notification).block();
  }

  private Flux<ServerSentEvent<NotificationLiveUpdateResponse>> subscribe(final String tenantId) {
    return webTestClient
        .get()
        .uri("/notifications:subscribe")
        .header("X-Tenant-Id", tenantId)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(
            new ParameterizedTypeReference<ServerSentEvent<NotificationLiveUpdateResponse>>() {})
        .getResponseBody()
        .filter(event -> event.data() != null);
  }

  @Test
  void deliversTheInitialSnapshotThenEachLiveStatusChangeWithoutAnyManualRefresh() {
    final Notification notification = persistAccepted("tenant-1", "order-live-1");

    StepVerifier.create(subscribe("tenant-1"))
        .assertNext(
            event -> {
              assertEquals("UPSERT", event.data().action());
              assertEquals("PENDING", event.data().notification().status());
            })
        .then(
            () -> {
              final Notification reloaded =
                  notificationRepository.findById(notification.notificationId()).block();
              reloaded.markQueued();
              reloaded.markDelivered(AttemptOrigin.AUTOMATIC, ProviderId.of("simulated"));
              final List<DomainEvent> events = reloaded.pullEvents();
              notificationRepository.save(reloaded).block();
              eventPublisherPort.publish(events).block();
            })
        .assertNext(
            event -> {
              assertEquals("UPSERT", event.data().action());
              assertEquals("DELIVERED", event.data().notification().status());
            })
        .thenCancel()
        .verify(Duration.ofSeconds(20));
  }

  @Test
  void neverDeliversAnUpdateBelongingToAnotherTenant() {
    persistAccepted("tenant-1", "order-live-2");
    final Notification otherTenantNotification = persistAccepted("tenant-2", "order-live-3");

    StepVerifier.create(subscribe("tenant-1"))
        .assertNext(event -> assertEquals("order-live-2", event.data().notification().externalId()))
        .then(
            () -> {
              final Notification reloaded =
                  notificationRepository.findById(otherTenantNotification.notificationId()).block();
              reloaded.markQueued();
              reloaded.markDelivered(AttemptOrigin.AUTOMATIC, ProviderId.of("simulated"));
              final List<DomainEvent> events = reloaded.pullEvents();
              notificationRepository.save(reloaded).block();
              eventPublisherPort.publish(events).block();
            })
        .expectNoEvent(Duration.ofSeconds(5))
        .thenCancel()
        .verify(Duration.ofSeconds(20));
  }
}
