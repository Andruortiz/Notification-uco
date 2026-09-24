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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class FcmPushDeliveryE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FcmTestCredentials CREDENTIALS = FcmTestCredentials.shared();
  private static final String ACCESS_TOKEN = "e2e-access-" + "token";
  private static final String RECIPIENT = "device-" + "token-e2e-5678";
  private static final String TENANT = "tenant-1";
  private static final String SEND_PATH =
      "/v1/projects/" + FcmTestCredentials.PROJECT_ID + "/messages:send";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer fakeAuthorization;
  static FakeProviderServer fakeFcm;

  @DynamicPropertySource
  static void fcmProperties(final DynamicPropertyRegistry registry) {
    fakeAuthorization = FakeProviderServer.start();
    fakeAuthorization.nextResponse(
        200, "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":3600}");
    fakeFcm = FakeProviderServer.start();
    registry.add("notification.provider.fcm.credentials-json", CREDENTIALS::json);
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
    fakeFcm.reset();
    fakeFcm.nextResponse(200, "{\"name\":\"projects/demo-project/messages/0:e2e-0001\"}");

    logAppender = new ListAppender<>();
    logAppender.start();
    rootLogger().addAppender(logAppender);

    rabbitAdmin = new RabbitAdmin(connectionFactory);
    final Queue queue = QueueBuilder.nonDurable("fcm-e2e-events-" + UUID.randomUUID()).build();
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
        mongoTemplate.findById("PUSH", ChannelCatalogDocument.class).block();
    assertNotNull(seeded, "el catalogo sembrado debe declarar el canal PUSH");
    mongoTemplate
        .save(new ChannelCatalogDocument("PUSH", List.of(providers), seeded.contentSchema()))
        .block();
    final ProviderId expected = ProviderId.of(providers[0]);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort.findActiveRoute(ChannelType.of("PUSH"), TenantId.of(TENANT)).block();
      if (route != null && route.preferredProvider().equals(expected)) {
        return;
      }
    }
    throw new IllegalStateException("El catalogo nunca prefirio " + providers[0]);
  }

  private Map<String, Object> pushRequest(
      final String externalId, final String recipient, final String subject, final String body) {
    final Map<String, Object> request = new HashMap<>();
    request.put("externalId", externalId);
    request.put("channelType", "PUSH");
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

  private void assertRejectedAtAcceptance(
      final Map<String, Object> request, final String expectedLimit) {
    final Map<String, Object> error =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(error);
    assertTrue(String.valueOf(error.get("message")).contains(expectedLimit));
    assertNull(
        notificationRepository
            .findByTenantAndExternalId(
                TenantId.of(TENANT), ExternalId.of(request.get("externalId").toString()))
            .block());
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

  private static JsonNode sentMessage(final int index) {
    try {
      return MAPPER.readTree(fakeFcm.requests().get(index).body()).get("message");
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String fcmError(final int code, final String status, final String errorCode) {
    return "{\"error\":{\"code\":"
        + code
        + ",\"message\":\"Requested entity was not found for "
        + RECIPIENT
        + "\",\"status\":\""
        + status
        + "\",\"details\":[{\"errorCode\":\""
        + errorCode
        + "\"}]}}";
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
  void deliversThroughFcmAndRecordsItsProviderId() {
    preferProviders("fcm", "simulated");

    final String notificationId =
        accept(pushRequest("push-ok-1", RECIPIENT, "Pedido enviado", "Tu pedido salio hoy"));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("fcm", delivered.get("providerId"));
    assertEquals(1, fakeFcm.requests().size());
    final FakeProviderServer.RecordedRequest request = fakeFcm.requests().get(0);
    assertEquals(SEND_PATH, request.path());
    assertEquals("Bearer " + ACCESS_TOKEN, request.header("Authorization"));
    final JsonNode message = sentMessage(0);
    assertEquals(RECIPIENT, message.get("token").asText());
    assertEquals("Pedido enviado", message.get("notification").get("title").asText());
    assertEquals("Tu pedido salio hoy", message.get("notification").get("body").asText());
  }

  @Test
  void doesNotCallFcmWhenTheSimulatedProviderIsPreferred() {
    preferProviders("simulated", "fcm");

    final String notificationId = accept(pushRequest("push-sim-1", RECIPIENT, null, "Hola"));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("simulated", delivered.get("providerId"));
    assertTrue(fakeFcm.requests().isEmpty());
  }

  @Test
  void anUnregisteredDeviceEndsInFailedAfterASingleCallWithoutRetries()
      throws InterruptedException {
    preferProviders("fcm", "simulated");
    fakeFcm.nextResponse(404, fcmError(404, "NOT_FOUND", "UNREGISTERED"));

    final String notificationId = accept(pushRequest("push-404-1", RECIPIENT, null, "Hola"));

    assertEquals("FAILED", awaitStatus(notificationId, "FAILED").get("status"));
    Thread.sleep(2_000);
    assertEquals(1, fakeFcm.requests().size());
    assertEquals("FAILED", status(notificationId).get("status"));
  }

  @Test
  void anInvalidArgumentEndsInFailedStatus() {
    preferProviders("fcm", "simulated");
    fakeFcm.nextResponse(400, fcmError(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT"));

    final String notificationId = accept(pushRequest("push-400-1", RECIPIENT, null, "Hola"));

    assertEquals("FAILED", awaitStatus(notificationId, "FAILED").get("status"));
  }

  @Test
  void anUnavailableProviderEndsInRecoverableStatus() {
    preferProviders("fcm", "simulated");
    fakeFcm.nextResponse(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE"));

    final String notificationId = accept(pushRequest("push-503-1", RECIPIENT, null, "Hola"));

    assertEquals("RECOVERABLE", awaitStatus(notificationId, "RECOVERABLE").get("status"));
  }

  @Test
  void aTitleOf100AndABodyOf900CharactersAreAcceptedAndDelivered() {
    preferProviders("fcm", "simulated");

    final String notificationId =
        accept(pushRequest("push-limit-1", RECIPIENT, "t".repeat(100), "b".repeat(900)));

    assertEquals("DELIVERED", awaitStatus(notificationId, "DELIVERED").get("status"));
    assertEquals("b".repeat(900), sentMessage(0).get("notification").get("body").asText());
  }

  @Test
  void aTitleOrBodyOverTheChannelLimitIsRejectedAtAcceptanceWithoutCallingFcm() {
    preferProviders("fcm", "simulated");

    assertRejectedAtAcceptance(
        pushRequest("push-title-101", RECIPIENT, "t".repeat(101), "Hola"), "100");
    assertRejectedAtAcceptance(
        pushRequest("push-body-901", RECIPIENT, "Titulo", "b".repeat(901)), "900");

    assertTrue(fakeFcm.requests().isEmpty());
  }

  @Test
  void aNotificationWithoutSubjectIsSentWithoutTitle() {
    preferProviders("fcm", "simulated");

    final String notificationId = accept(pushRequest("push-notitle-1", RECIPIENT, null, "Hola"));

    assertEquals("DELIVERED", awaitStatus(notificationId, "DELIVERED").get("status"));
    assertFalse(sentMessage(0).get("notification").has("title"));
  }

  @Test
  void aRecipientThatIsNotADeviceTokenIsSentAsIsAndEndsInFailed() {
    preferProviders("fcm", "simulated");
    fakeFcm.nextResponse(400, fcmError(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT"));

    final String notificationId =
        accept(pushRequest("push-email-1", "alice@example.com", null, "Hola"));

    assertEquals("FAILED", awaitStatus(notificationId, "FAILED").get("status"));
    assertEquals(1, fakeFcm.requests().size());
    assertEquals("alice@example.com", sentMessage(0).get("token").asText());
  }

  @Test
  void noCredentialTokenContentOrFullDeviceTokenLeaksIntoLogsOrPublishedEvents() {
    preferProviders("fcm", "simulated");
    final String secretTitle = "TituloSecretoE2E";
    final String secretBody = "CuerpoSecretoE2E";

    final String deliveredId =
        accept(pushRequest("push-noleak-1", RECIPIENT, secretTitle, secretBody));
    awaitStatus(deliveredId, "DELIVERED");

    fakeFcm.nextResponse(404, fcmError(404, "NOT_FOUND", "UNREGISTERED"));
    final String rejectedId =
        accept(pushRequest("push-noleak-2", RECIPIENT, secretTitle, secretBody));
    awaitStatus(rejectedId, "FAILED");

    final List<String> forbidden = new ArrayList<>();
    forbidden.addAll(
        List.of(
            CREDENTIALS.privateKeyFragment(),
            FcmTestCredentials.CLIENT_EMAIL,
            ACCESS_TOKEN,
            secretTitle,
            secretBody,
            RECIPIENT,
            "Requested entity"));
    fakeAuthorization.requests().stream()
        .map(request -> URLDecoder.decode(request.body(), StandardCharsets.UTF_8))
        .map(body -> body.substring(body.indexOf("assertion=") + "assertion=".length()))
        .forEach(forbidden::add);

    final String logs = logs();
    assertTrue(logs.contains("recipient=***5678"), "the masked device token must be logged");
    assertTrue(
        logs.contains("providerErrorCode=UNREGISTERED"), "the provider error code must be logged");
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
