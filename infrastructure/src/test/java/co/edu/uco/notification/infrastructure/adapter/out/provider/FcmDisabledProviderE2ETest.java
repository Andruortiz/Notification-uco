package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class FcmDisabledProviderE2ETest {

  private static final String TENANT = "tenant-1";
  private static final String RECIPIENT = "device-" + "token-disabled-4321";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer fakeAuthorization;
  static FakeProviderServer fakeFcm;

  @DynamicPropertySource
  static void fcmUrls(final DynamicPropertyRegistry registry) {
    fakeAuthorization = FakeProviderServer.start();
    fakeFcm = FakeProviderServer.start();
    registry.add("notification.provider.fcm.base-url", () -> fakeFcm.baseUrl());
    registry.add(
        "notification.provider.fcm.token-url", () -> fakeAuthorization.baseUrl() + "/token");
  }

  @AfterAll
  static void stopFakeServers() {
    fakeAuthorization.stop();
    fakeFcm.stop();
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private NotificationRepository notificationRepository;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    fakeAuthorization.reset();
    fakeFcm.reset();
  }

  private ChannelRoute awaitPushRoute(final String preferredProvider) {
    final ProviderId expected = ProviderId.of(preferredProvider);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort.findActiveRoute(ChannelType.of("PUSH"), TenantId.of(TENANT)).block();
      if (route != null && route.preferredProvider().equals(expected)) {
        return route;
      }
    }
    throw new IllegalStateException("El catalogo nunca prefirio " + preferredProvider);
  }

  private String acceptPush(final String externalId) {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of(
                    "externalId", externalId,
                    "channelType", "PUSH",
                    "recipientId", "recipient-1",
                    "recipientAddress", RECIPIENT,
                    "subject", "Aviso",
                    "body", "Hola desde el canal PUSH",
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
        .header("X-Tenant-Id", TENANT)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private Map<String, Object> awaitStatus(final String notificationId, final String expected) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    Map<String, Object> current = status(notificationId);
    while (Instant.now().isBefore(deadline)
        && !expected.equals(String.valueOf(current.get("status")))) {
      current = status(notificationId);
    }
    return current;
  }

  private Message awaitDeadLetteredMessage(final String notificationId) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
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
  void theDefaultConfigurationSeedsThePushChannelAndDeliversThroughTheSimulatedProvider() {
    final ChannelRoute route = awaitPushRoute("simulated");

    assertEquals(List.of(ProviderId.of("simulated"), ProviderId.of("fcm")), route.providers());
    assertTrue(route.contentSchema().contains("\"maxLength\":900"));
    assertTrue(route.contentSchema().contains("\"maxLength\":100"));

    final String notificationId = acceptPush("push-default-1");
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("simulated", delivered.get("providerId"));
    assertTrue(fakeAuthorization.requests().isEmpty());
    assertTrue(fakeFcm.requests().isEmpty());
  }

  @Test
  void aNotificationRoutedToTheDisabledProviderIsNeverDeliveredNorLostAndNeverCallsFcm() {
    final ChannelCatalogDocument seeded =
        mongoTemplate.findById("PUSH", ChannelCatalogDocument.class).block();
    assertNotNull(seeded);
    mongoTemplate
        .save(
            new ChannelCatalogDocument("PUSH", List.of("fcm", "simulated"), seeded.contentSchema()))
        .block();
    awaitPushRoute("fcm");

    final String notificationId = acceptPush("push-disabled-1");
    final Message deadLettered = awaitDeadLetteredMessage(notificationId);

    assertNotNull(deadLettered, "el mensaje debe terminar en la cola de mensajes muertos");
    final Object cause =
        deadLettered
            .getMessageProperties()
            .getHeaders()
            .get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE);
    assertNotNull(cause, "el header de causa debe estar presente");
    assertTrue(cause.toString().contains("fcm"));
    assertTrue(cause.toString().contains("FCM_CREDENTIALS_JSON"));
    assertFalse(cause.toString().contains(RECIPIENT));

    final Notification stored =
        notificationRepository.findById(NotificationId.of(notificationId)).block();
    assertNotNull(stored);
    assertTrue(stored.deliveryAttempts().isEmpty());
    assertEquals("PENDING", status(notificationId).get("status"));
    assertTrue(fakeAuthorization.requests().isEmpty());
    assertTrue(fakeFcm.requests().isEmpty());
  }
}
