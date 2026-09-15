package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument;
import java.time.Duration;
import java.time.Instant;
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
class NotificationLiveUpdatesFilteredE2ETest {

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

  private Notification persistInStatus(
      final String externalId,
      final NotificationStatus status,
      final List<DeliveryAttempt> attempts) {
    final Notification notification =
        Notification.reconstitute(
            NotificationId.newId(),
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of(externalId),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            status,
            new NotificationMetadata(Instant.now(), null),
            attempts);
    return notificationRepository.save(notification).block();
  }

  private Flux<ServerSentEvent<NotificationLiveUpdateResponse>> subscribeFilteredByStatus(
      final NotificationStatus status) {
    return webTestClient
        .get()
        .uri("/notifications:subscribe?status=" + status)
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(
            new ParameterizedTypeReference<ServerSentEvent<NotificationLiveUpdateResponse>>() {})
        .getResponseBody()
        .filter(event -> event.data() != null);
  }

  private void publishTransition(
      final Notification notification, final java.util.function.Consumer<Notification> transition) {
    transition.accept(notification);
    final List<DomainEvent> events = notification.pullEvents();
    notificationRepository.save(notification).block();
    eventPublisherPort.publish(events).block();
  }

  @Test
  void aNotificationOutsideTheFilterEntersTheViewWhenItStartsMatching() {
    // Ya en FAILED antes de suscribirse: aparece en la foto inicial, confirmando que la
    // suscripción ya está activa antes de disparar la transición de outsideFilter — sin esto,
    // publishTransition podría ejecutarse antes de que el servidor empiece a escuchar el fanout.
    persistInStatus("order-filter-0", NotificationStatus.FAILED, List.of());
    final Notification outsideFilter =
        persistInStatus("order-filter-1", NotificationStatus.IN_PROCESS, List.of());

    StepVerifier.create(subscribeFilteredByStatus(NotificationStatus.FAILED))
        .assertNext(
            event -> assertEquals("order-filter-0", event.data().notification().externalId()))
        .then(
            () -> {
              final Notification reloaded =
                  notificationRepository.findById(outsideFilter.notificationId()).block();
              publishTransition(
                  reloaded, n -> n.markFailed(AttemptOrigin.AUTOMATIC, ProviderId.of("simulated")));
            })
        .assertNext(
            event -> {
              assertEquals("UPSERT", event.data().action());
              assertEquals("order-filter-1", event.data().notification().externalId());
              assertEquals("FAILED", event.data().notification().status());
            })
        .thenCancel()
        .verify(Duration.ofSeconds(20));
  }

  @Test
  void aVisibleNotificationLeavesTheViewWhenItStopsMatching() {
    final Notification visible =
        persistInStatus(
            "order-filter-2",
            NotificationStatus.RECOVERABLE,
            List.of(
                DeliveryAttempt.of(
                    Instant.now(),
                    AttemptResult.RECOVERABLE_FAILURE,
                    AttemptOrigin.AUTOMATIC,
                    ProviderId.of("simulated"))));

    StepVerifier.create(subscribeFilteredByStatus(NotificationStatus.RECOVERABLE))
        .assertNext(
            event -> {
              assertEquals("UPSERT", event.data().action());
              assertEquals("order-filter-2", event.data().notification().externalId());
            })
        .then(
            () -> {
              final Notification reloaded =
                  notificationRepository.findById(visible.notificationId()).block();
              publishTransition(reloaded, Notification::requeue);
            })
        .assertNext(
            event -> {
              assertEquals("REMOVE", event.data().action());
              assertEquals("order-filter-2", event.data().notification().externalId());
            })
        .thenCancel()
        .verify(Duration.ofSeconds(20));
  }
}
