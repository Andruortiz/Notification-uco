package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
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
      "notification.provider.brevo.api-key=e2e-test-secret-key",
      "notification.provider.brevo.sender-email=sender@example.com",
      "notification.provider.brevo.sender-name=Notification UCO"
    })
@Testcontainers
class BrevoEmailDeliveryE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer FAKE_BREVO;

  @DynamicPropertySource
  static void brevoBaseUrl(final DynamicPropertyRegistry registry) {
    FAKE_BREVO = FakeProviderServer.start();
    registry.add("notification.provider.brevo.base-url", () -> FAKE_BREVO.baseUrl());
  }

  @AfterAll
  static void stopFakeBrevo() {
    FAKE_BREVO.stop();
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  @Autowired private ConnectionFactory connectionFactory;

  private WebTestClient webTestClient;
  private ListAppender<ILoggingEvent> logAppender;
  private LinkedBlockingQueue<byte[]> capturedEvents;
  private RabbitAdmin rabbitAdmin;
  private String eventsListenerQueue;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    FAKE_BREVO.reset();

    logAppender = new ListAppender<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logAppender);

    capturedEvents = new LinkedBlockingQueue<>();
    rabbitAdmin = new RabbitAdmin(connectionFactory);
    final AnonymousQueue queue = new AnonymousQueue();
    final FanoutExchange eventsExchange =
        new FanoutExchange(rabbitTopologyProperties.eventsExchange());
    final Binding binding = BindingBuilder.bind(queue).to(eventsExchange);
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(binding);
    eventsListenerQueue = queue.getName();
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logAppender);
    rabbitAdmin.deleteQueue(eventsListenerQueue);
  }

  private void makeBrevoPreferred() {
    mongoTemplate
        .save(new ChannelCatalogDocument("EMAIL", List.of("brevo", "simulated"), null))
        .block();
    awaitRoute("brevo");
  }

  private void makeSimulatedPreferred() {
    mongoTemplate
        .save(new ChannelCatalogDocument("EMAIL", List.of("simulated", "brevo"), null))
        .block();
    awaitRoute("simulated");
  }

  private void awaitRoute(final String expectedProvider) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final var route =
          channelCatalogPort
              .findActiveRoute(
                  co.edu.uco.notification.core.domain.valueobject.ChannelType.of("EMAIL"),
                  co.edu.uco.notification.core.domain.valueobject.TenantId.of("tenant-1"))
              .block();
      if (route != null
          && route
              .preferredProvider()
              .equals(
                  co.edu.uco.notification.core.domain.valueobject.ProviderId.of(
                      expectedProvider))) {
        return;
      }
    }
    throw new IllegalStateException("El catalogo nunca prefirio " + expectedProvider);
  }

  private String acceptNotification(
      final String externalId, final String subject, final String body) {
    final Map<String, Object> requestBody =
        new java.util.HashMap<>(
            Map.of(
                "externalId", externalId,
                "channelType", "EMAIL",
                "recipientId", "recipient-1",
                "recipientAddress", "alice@example.com",
                "body", body,
                "priority", "NORMAL"));
    if (subject != null) {
      requestBody.put("subject", subject);
    }
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", "tenant-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
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

  private Map<String, Object> awaitStatus(
      final String notificationId, final String expectedStatus) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    Map<String, Object> current = status(notificationId);
    while (Instant.now().isBefore(deadline)
        && !expectedStatus.equals(String.valueOf(current.get("status")))) {
      current = status(notificationId);
    }
    return current;
  }

  @Test
  void deliversThroughBrevoAndRecordsItsProviderId() {
    makeBrevoPreferred();

    final String notificationId = acceptNotification("brevo-ok-1", "Hola", "Contenido del correo");
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("brevo", delivered.get("providerId"));
    assertEquals(1, FAKE_BREVO.requests().size());
    assertEquals("/v3/smtp/email", FAKE_BREVO.requests().get(0).path());
  }

  @Test
  void doesNotCallBrevoWhenSimulatedIsPreferred() {
    makeSimulatedPreferred();

    final String notificationId = acceptNotification("brevo-skip-1", "Hola", "Contenido");
    awaitStatus(notificationId, "DELIVERED");

    assertTrue(FAKE_BREVO.requests().isEmpty());
  }

  @Test
  void notificationWithoutSubjectFailsPermanentlyWithoutCallingBrevo() {
    makeBrevoPreferred();

    final String notificationId = acceptNotification("brevo-no-subject-1", null, "Contenido");
    final Map<String, Object> failed = awaitStatus(notificationId, "FAILED");

    assertEquals("FAILED", failed.get("status"));
    assertTrue(FAKE_BREVO.requests().isEmpty());
  }

  @Test
  void aPermanentProviderRejectionEndsInFailedStatus() {
    makeBrevoPreferred();
    FAKE_BREVO.nextResponse(400, "{\"code\":\"invalid_parameter\"}");

    final String notificationId = acceptNotification("brevo-400-1", "Hola", "Contenido");
    final Map<String, Object> failed = awaitStatus(notificationId, "FAILED");

    assertEquals("FAILED", failed.get("status"));
    assertEquals(1, FAKE_BREVO.requests().size());
  }

  @Test
  void aTemporaryProviderFailureEndsInRecoverableStatus() {
    makeBrevoPreferred();
    FAKE_BREVO.nextResponse(500, "{\"message\":\"internal\"}");

    final String notificationId = acceptNotification("brevo-500-1", "Hola", "Contenido");
    final Map<String, Object> recoverable = awaitStatus(notificationId, "RECOVERABLE");

    assertEquals("RECOVERABLE", recoverable.get("status"));
  }

  @Test
  void noSensitiveDataLeaksIntoLogsOrPublishedEvents() throws InterruptedException {
    makeBrevoPreferred();

    final String secretApiKey = "e2e-test-secret-key";
    final String secretSubject = "SecretSubjectMarker";
    final String secretBody = "SecretBodyMarker";
    final String secretAddress = "alice@example.com";

    final String notificationId = acceptNotification("brevo-noleak-1", secretSubject, secretBody);
    awaitStatus(notificationId, "DELIVERED");

    final String logs =
        logAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", String::concat);
    assertFalse(logs.contains(secretApiKey), "logs must not contain the api key");
    assertFalse(logs.contains(secretSubject), "logs must not contain the subject");
    assertFalse(logs.contains(secretBody), "logs must not contain the body");
    assertFalse(logs.contains(secretAddress), "logs must not contain the recipient address");

    final Message eventMessage = rabbitTemplate.receive(eventsListenerQueue, 3_000);
    assertNotNull(eventMessage, "at least one domain event must have been published");
    final String eventBody =
        new String(eventMessage.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(eventBody.contains(secretApiKey));
    assertFalse(eventBody.contains(secretSubject));
    assertFalse(eventBody.contains(secretBody));
    assertFalse(eventBody.contains(secretAddress));

    final Message dispatchMessage =
        rabbitTemplate.receive(rabbitTopologyProperties.dispatch().queue(), 500);
    if (dispatchMessage != null) {
      final String dispatchBody =
          new String(dispatchMessage.getBody(), java.nio.charset.StandardCharsets.UTF_8);
      assertFalse(dispatchBody.contains(secretApiKey));
      assertFalse(dispatchBody.contains(secretSubject));
      assertFalse(dispatchBody.contains(secretBody));
    }

    assertEquals(1, FAKE_BREVO.requests().size());
    assertEquals(secretApiKey, FAKE_BREVO.requests().get(0).header("api-key"));
  }
}
