package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
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
      "notification.catalog.channels.EMAIL.providers[0]=fake-a",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class ProviderRoutingE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final class RecordingNotificationSender implements NotificationSenderPort {

    private final ProviderId providerId;
    private final List<NotificationId> received = new CopyOnWriteArrayList<>();

    RecordingNotificationSender(final ProviderId providerId) {
      this.providerId = providerId;
    }

    @Override
    public Mono<AttemptResult> send(final Notification notification) {
      received.add(notification.notificationId());
      return Mono.just(AttemptResult.ACCEPTED);
    }

    @Override
    public ProviderId providerId() {
      return providerId;
    }

    @Override
    public Optional<String> disabledReason() {
      return Optional.empty();
    }

    List<NotificationId> received() {
      return List.copyOf(received);
    }
  }

  @TestConfiguration
  static class FakeProvidersConfiguration {

    @Bean
    RecordingNotificationSender fakeA() {
      return new RecordingNotificationSender(ProviderId.of("fake-a"));
    }

    @Bean
    RecordingNotificationSender fakeB() {
      return new RecordingNotificationSender(ProviderId.of("fake-b"));
    }
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private NotificationRepository notificationRepository;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  @Autowired private SimulatedNotificationProvider simulatedNotificationProvider;

  @Autowired
  @Qualifier("fakeA")
  private RecordingNotificationSender fakeA;

  @Autowired
  @Qualifier("fakeB")
  private RecordingNotificationSender fakeB;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    mongoTemplate.save(new ChannelCatalogDocument("SMS", List.of("fantasma"), null)).block();
  }

  private String acceptNotification(final String externalId, final String channelType) {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", "tenant-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of(
                    "externalId", externalId,
                    "channelType", channelType,
                    "recipientId", "recipient-1",
                    "recipientAddress", "alice@example.com",
                    "subject", "Subject",
                    "body", "Body",
                    "priority", "NORMAL"))
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertNotNull(response);
    return response.get("notificationId").toString();
  }

  private Map<String, Object> status(final String notificationId) {
    return webTestClient
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private void awaitRoute(final String channelType, final String expectedProvider) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort
              .findActiveRoute(ChannelType.of(channelType), TenantId.of("tenant-1"))
              .block();
      if (route != null && route.preferredProvider().equals(ProviderId.of(expectedProvider))) {
        return;
      }
    }
    throw new IllegalStateException(
        "El catalogo nunca expuso " + expectedProvider + " para " + channelType);
  }

  private Map<String, Object> awaitStatus(
      final String notificationId, final String expectedStatus) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
    Map<String, Object> current = status(notificationId);
    while (Instant.now().isBefore(deadline)
        && !expectedStatus.equals(String.valueOf(current.get("status")))) {
      current = status(notificationId);
    }
    return current;
  }

  private Message awaitDeadLetteredMessage(final String notificationId) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
    while (Instant.now().isBefore(deadline)) {
      final Message received = rabbitTemplate.receive(rabbitTopologyProperties.dlq().queue(), 500);
      if (received != null
          && notificationId.equals(new String(received.getBody(), StandardCharsets.UTF_8))) {
        return received;
      }
    }
    return null;
  }

  @Test
  void dispatchesThroughTheAdapterOfThePreferredProviderAndNotThroughAnyOther() {
    awaitRoute("EMAIL", "fake-a");

    final String notificationId = acceptNotification("routing-ok-1", "EMAIL");
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("fake-a", delivered.get("providerId"));
    assertEquals(List.of(NotificationId.of(notificationId)), fakeA.received());
    assertTrue(fakeB.received().isEmpty());
  }

  @Test
  void keepsTheSimulatedProviderAvailableAlongsideTheOtherAdapters() {
    assertEquals(ProviderId.of("simulated"), simulatedNotificationProvider.providerId());
    assertEquals(ProviderId.of("fake-a"), fakeA.providerId());
    assertEquals(ProviderId.of("fake-b"), fakeB.providerId());
  }

  @Test
  void leavesATraceableFailureWhenThePreferredProviderHasNoAdapter() {
    awaitRoute("SMS", "fantasma");

    final String notificationId = acceptNotification("routing-missing-1", "SMS");
    final Message deadLettered = awaitDeadLetteredMessage(notificationId);

    assertNotNull(deadLettered, "el mensaje debe terminar en la cola de mensajes muertos");
    final Object cause =
        deadLettered
            .getMessageProperties()
            .getHeaders()
            .get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE);
    assertNotNull(cause, "el header de causa debe estar presente");
    assertTrue(cause.toString().contains("fantasma"));

    final Notification stored =
        notificationRepository.findById(NotificationId.of(notificationId)).block();
    assertNotNull(stored);
    assertTrue(stored.deliveryAttempts().isEmpty());
    assertEquals("PENDING", status(notificationId).get("status"));
    assertTrue(fakeA.received().stream().noneMatch(id -> id.value().equals(notificationId)));
    assertTrue(fakeB.received().stream().noneMatch(id -> id.value().equals(notificationId)));
  }
}
