package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.provider.twilio.account-sid=" + WhatsAppDisabledProviderE2ETest.ACCOUNT_SID,
      "notification.provider.twilio.auth-token=" + WhatsAppDisabledProviderE2ETest.AUTH_TOKEN,
      "notification.provider.twilio.from-number=+15005550006",
      "notification.provider.twilio-whatsapp.account-sid="
          + WhatsAppDisabledProviderE2ETest.ACCOUNT_SID,
      "notification.provider.twilio-whatsapp.auth-token="
          + WhatsAppDisabledProviderE2ETest.AUTH_TOKEN
    })
@Testcontainers
class WhatsAppDisabledProviderE2ETest {

  static final String ACCOUNT_SID = "AC" + "00112233445566778899aabbccddeeff";
  static final String AUTH_TOKEN = "e2e-disabled-secret-token";
  private static final String TENANT = "tenant-1";
  private static final String RECIPIENT = "+573001234567";
  private static final String TEMPLATE_ID = "HX" + "0123456789abcdef0123456789abcdef";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer fakeTwilio;

  @DynamicPropertySource
  static void twilioBaseUrls(final DynamicPropertyRegistry registry) {
    fakeTwilio = FakeProviderServer.start();
    registry.add("notification.provider.twilio.base-url", () -> fakeTwilio.baseUrl());
    registry.add("notification.provider.twilio-whatsapp.base-url", () -> fakeTwilio.baseUrl());
  }

  @AfterAll
  static void stopFakeTwilio() {
    fakeTwilio.stop();
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
    fakeTwilio.reset();
  }

  private ChannelRoute awaitRoute(final String channel, final String preferredProvider) {
    final ProviderId expected = ProviderId.of(preferredProvider);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort.findActiveRoute(ChannelType.of(channel), TenantId.of(TENANT)).block();
      if (route != null && route.preferredProvider().equals(expected)) {
        return route;
      }
    }
    throw new IllegalStateException("El catalogo nunca prefirio " + preferredProvider);
  }

  private void prefer(final String channel, final String... providers) {
    final ChannelCatalogDocument seeded =
        mongoTemplate.findById(channel, ChannelCatalogDocument.class).block();
    assertNotNull(seeded);
    mongoTemplate
        .save(new ChannelCatalogDocument(channel, List.of(providers), seeded.contentSchema()))
        .block();
    awaitRoute(channel, providers[0]);
  }

  private String accept(final Map<String, Object> request) {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(TENANT))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response.get("notificationId").toString();
  }

  private static Map<String, Object> request(final String externalId, final String channel) {
    final Map<String, Object> request = new HashMap<>();
    request.put("externalId", externalId);
    request.put("channelType", channel);
    request.put("recipientId", "recipient-1");
    request.put("recipientAddress", RECIPIENT);
    request.put("priority", "NORMAL");
    return request;
  }

  private static Map<String, Object> textRequest(final String externalId, final String channel) {
    final Map<String, Object> request = request(externalId, channel);
    request.put("body", "Hola desde el canal");
    return request;
  }

  private static Map<String, Object> templateRequest(final String externalId) {
    final Map<String, Object> request = request(externalId, "WHATSAPP");
    request.put(
        "providerTemplate", Map.of("templateId", TEMPLATE_ID, "variables", Map.of("1", "Alice")));
    return request;
  }

  private Map<String, Object> status(final String notificationId) {
    return webTestClient
        .get()
        .uri("/notifications/detail?notificationId={id}", notificationId)
        .header("Authorization", TestTokens.bearer(TENANT))
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
  void theDefaultConfigurationSeedsTheChannelAndDeliversThroughTheSimulatedProvider() {
    prefer("WHATSAPP", "simulated", "twilio-whatsapp");
    final ChannelRoute route = awaitRoute("WHATSAPP", "simulated");

    assertEquals(
        List.of(ProviderId.of("simulated"), ProviderId.of("twilio-whatsapp")), route.providers());

    final String textId = accept(textRequest("wa-default-1", "WHATSAPP"));
    final String templateId = accept(templateRequest("wa-default-2"));

    assertEquals("simulated", awaitStatus(textId, "DELIVERED").get("providerId"));
    assertEquals("simulated", awaitStatus(templateId, "DELIVERED").get("providerId"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void aNotificationRoutedToTheDisabledProviderIsNeverDeliveredNorLostAndNeverCallsTwilio() {
    prefer("WHATSAPP", "twilio-whatsapp", "simulated");

    final String notificationId = accept(textRequest("wa-disabled-1", "WHATSAPP"));
    final Message deadLettered = awaitDeadLetteredMessage(notificationId);

    assertNotNull(deadLettered, "el mensaje debe terminar en la cola de mensajes muertos");
    final Object cause =
        deadLettered
            .getMessageProperties()
            .getHeaders()
            .get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE);
    assertNotNull(cause, "el header de causa debe estar presente");
    assertTrue(cause.toString().contains("twilio-whatsapp"));
    assertTrue(cause.toString().contains("TWILIO_WHATSAPP_FROM_NUMBER"));
    assertFalse(cause.toString().contains(RECIPIENT));
    assertFalse(cause.toString().contains(AUTH_TOKEN));

    final Notification stored =
        notificationRepository.findById(NotificationId.of(notificationId)).block();
    assertNotNull(stored);
    assertTrue(stored.deliveryAttempts().isEmpty());
    assertNull(stored.dispatchReservedAt());
    assertEquals("PENDING", status(notificationId).get("status"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void theSmsProviderStaysEnabledWhenOnlyTheWhatsAppSenderNumberIsMissing() {
    prefer("SMS", "twilio", "simulated");

    final String notificationId = accept(textRequest("sms-independent-1", "SMS"));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("twilio", delivered.get("providerId"));
    assertEquals(1, fakeTwilio.requests().size());
  }
}
