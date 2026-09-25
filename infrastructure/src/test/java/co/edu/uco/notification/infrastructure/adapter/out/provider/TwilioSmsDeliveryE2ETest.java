package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.provider.twilio.account-sid=" + TwilioSmsDeliveryE2ETest.ACCOUNT_SID,
      "notification.provider.twilio.auth-token=" + TwilioSmsDeliveryE2ETest.AUTH_TOKEN,
      "notification.provider.twilio.from-number=" + TwilioSmsDeliveryE2ETest.FROM_NUMBER
    })
@Testcontainers
class TwilioSmsDeliveryE2ETest {

  static final String ACCOUNT_SID = "AC" + "00112233445566778899aabbccddeeff";
  static final String AUTH_TOKEN = "e2e-twilio-secret-token";
  static final String FROM_NUMBER = "+15005550006";
  private static final String RECIPIENT = "+573001234567";
  private static final String TENANT = "tenant-1";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer fakeTwilio;

  @DynamicPropertySource
  static void twilioBaseUrl(final DynamicPropertyRegistry registry) {
    fakeTwilio = FakeProviderServer.start();
    registry.add("notification.provider.twilio.base-url", () -> fakeTwilio.baseUrl());
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

  @Autowired private ConnectionFactory connectionFactory;

  private WebTestClient webTestClient;
  private ListAppender<ILoggingEvent> logAppender;
  private RabbitAdmin rabbitAdmin;
  private String eventsListenerQueue;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    fakeTwilio.reset();
    fakeTwilio.nextResponse(201, "{\"sid\":\"SM00000000000000000000000000000001\"}");

    logAppender = new ListAppender<>();
    logAppender.start();
    rootLogger().addAppender(logAppender);

    rabbitAdmin = new RabbitAdmin(connectionFactory);
    final Queue queue = QueueBuilder.nonDurable("twilio-e2e-events-" + UUID.randomUUID()).build();
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue)
            .to(new FanoutExchange(rabbitTopologyProperties.eventsExchange())));
    eventsListenerQueue = queue.getName();
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(logAppender);
    rabbitAdmin.deleteQueue(eventsListenerQueue);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private void preferProviders(final String... providers) {
    final ChannelCatalogDocument seeded =
        mongoTemplate.findById("SMS", ChannelCatalogDocument.class).block();
    assertNotNull(seeded, "el catalogo sembrado debe declarar el canal SMS");
    mongoTemplate
        .save(new ChannelCatalogDocument("SMS", List.of(providers), seeded.contentSchema()))
        .block();
    final ProviderId expected = ProviderId.of(providers[0]);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort.findActiveRoute(ChannelType.of("SMS"), TenantId.of(TENANT)).block();
      if (route != null && route.preferredProvider().equals(expected)) {
        return;
      }
    }
    throw new IllegalStateException("El catalogo nunca prefirio " + providers[0]);
  }

  private Map<String, Object> smsRequest(
      final String externalId, final String recipient, final String subject, final String body) {
    final Map<String, Object> request = new HashMap<>();
    request.put("externalId", externalId);
    request.put("channelType", "SMS");
    request.put("recipientId", "recipient-1");
    request.put("recipientAddress", recipient);
    request.put("body", body);
    request.put("priority", "NORMAL");
    if (subject != null) {
      request.put("subject", subject);
    }
    return request;
  }

  private String accept(final Map<String, Object> request) {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", TENANT)
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

  private static Map<String, String> form(final String body) {
    return Arrays.stream(body.split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
  }

  private String logs() {
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
  }

  private List<String> publishedEvents() {
    final List<String> bodies = new ArrayList<>();
    Message message = rabbitTemplate.receive(eventsListenerQueue, 3_000);
    while (message != null) {
      bodies.add(new String(message.getBody(), StandardCharsets.UTF_8));
      message = rabbitTemplate.receive(eventsListenerQueue, 500);
    }
    return bodies;
  }

  @Test
  void deliversThroughTwilioWithoutTheSubjectAndRecordsItsProviderId() {
    preferProviders("twilio", "simulated");

    final String notificationId =
        accept(smsRequest("sms-ok-1", RECIPIENT, "AsuntoQueNoViaja", "Tu codigo es 1234"));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("twilio", delivered.get("providerId"));
    assertEquals(1, fakeTwilio.requests().size());
    final FakeProviderServer.RecordedRequest request = fakeTwilio.requests().get(0);
    assertEquals("/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json", request.path());
    assertEquals(
        Map.of("To", RECIPIENT, "From", FROM_NUMBER, "Body", "Tu codigo es 1234"),
        form(request.body()));
    assertFalse(request.body().contains("AsuntoQueNoViaja"));
  }

  @Test
  void doesNotCallTwilioWhenTheSimulatedProviderIsPreferred() {
    preferProviders("simulated", "twilio");

    final String notificationId = accept(smsRequest("sms-sim-1", RECIPIENT, null, "Hola"));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("simulated", delivered.get("providerId"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void aPermanentProviderRejectionEndsInFailedStatus() {
    preferProviders("twilio", "simulated");
    fakeTwilio.nextResponse(400, "{\"code\":21610,\"status\":400}");

    final String notificationId = accept(smsRequest("sms-400-1", RECIPIENT, null, "Hola"));

    assertEquals("FAILED", awaitStatus(notificationId, "FAILED").get("status"));
    assertEquals(1, fakeTwilio.requests().size());
  }

  @Test
  void aTemporaryProviderFailureEndsInRecoverableStatus() {
    preferProviders("twilio", "simulated");
    fakeTwilio.nextResponse(500, "{\"code\":20500,\"status\":500}");

    final String notificationId = accept(smsRequest("sms-500-1", RECIPIENT, null, "Hola"));

    assertEquals("RECOVERABLE", awaitStatus(notificationId, "RECOVERABLE").get("status"));
  }

  @Test
  void aBodyOfExactly160CharactersIsAcceptedAndDelivered() {
    preferProviders("twilio", "simulated");

    final String notificationId = accept(smsRequest("sms-160-1", RECIPIENT, null, "a".repeat(160)));

    assertEquals("DELIVERED", awaitStatus(notificationId, "DELIVERED").get("status"));
    assertEquals("a".repeat(160), form(fakeTwilio.requests().get(0).body()).get("Body"));
  }

  @Test
  void aBodyLongerThan160CharactersIsRejectedAtAcceptanceWithoutPersistingOrCallingTwilio() {
    preferProviders("twilio", "simulated");

    final Map<String, Object> error =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(smsRequest("sms-161-1", RECIPIENT, null, "a".repeat(161)))
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertNotNull(error);
    assertTrue(String.valueOf(error.get("message")).contains("160"));
    assertNull(
        notificationRepository
            .findByTenantAndExternalId(TenantId.of(TENANT), ExternalId.of("sms-161-1"))
            .block());
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void aRecipientWithoutInternationalFormatEndsInFailedWithoutCallingTwilio() {
    preferProviders("twilio", "simulated");

    final String notificationId =
        accept(smsRequest("sms-bad-recipient-1", "alice@example.com", null, "Hola"));

    assertEquals("FAILED", awaitStatus(notificationId, "FAILED").get("status"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void noSecretBodyOrFullNumberLeaksIntoLogsOrPublishedEvents() {
    preferProviders("twilio", "simulated");
    final String secretBody = "CuerpoSecretoSms";

    final String deliveredId = accept(smsRequest("sms-noleak-1", RECIPIENT, null, secretBody));
    awaitStatus(deliveredId, "DELIVERED");

    final String providerMessage = "The 'To' number " + RECIPIENT + " is not a valid phone number.";
    fakeTwilio.nextResponse(
        400, "{\"code\":21211,\"message\":\"" + providerMessage + "\",\"status\":400}");
    final String rejectedId = accept(smsRequest("sms-noleak-2", RECIPIENT, null, secretBody));
    awaitStatus(rejectedId, "FAILED");

    final String basicCredentials =
        Base64.getEncoder()
            .encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.ISO_8859_1));
    final List<String> forbidden =
        List.of(ACCOUNT_SID, AUTH_TOKEN, basicCredentials, secretBody, RECIPIENT, "not a valid");

    final String logs = logs();
    assertTrue(logs.contains("recipient=***4567"), "the masked number must be logged");
    assertTrue(logs.contains("providerErrorCode=21211"), "the provider error code must be logged");
    forbidden.forEach(
        value -> assertFalse(logs.contains(value), "logs must not contain a forbidden value"));

    final List<String> events = publishedEvents();
    assertFalse(events.isEmpty(), "domain events must have been published");
    events.forEach(
        event ->
            forbidden.forEach(
                value ->
                    assertFalse(
                        event.contains(value), "events must not contain a forbidden value")));

    final Message dispatchMessage =
        rabbitTemplate.receive(rabbitTopologyProperties.dispatch().queue(), 500);
    if (dispatchMessage != null) {
      final String dispatchBody = new String(dispatchMessage.getBody(), StandardCharsets.UTF_8);
      forbidden.forEach(value -> assertFalse(dispatchBody.contains(value)));
    }
  }
}
