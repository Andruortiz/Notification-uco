package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
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
class BrevoDisabledProviderE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private NotificationRepository notificationRepository;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  @Autowired private ApplicationContext applicationContext;

  private WebTestClient webTestClient;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    logAppender = new ListAppender<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logAppender);

    mongoTemplate
        .save(new ChannelCatalogDocument("EMAIL", List.of("brevo", "simulated"), null))
        .block();
    awaitRoute();
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logAppender);
  }

  private void awaitRoute() {
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
              .equals(co.edu.uco.notification.core.domain.valueobject.ProviderId.of("brevo"))) {
        return;
      }
    }
    throw new IllegalStateException("El catalogo nunca prefirio brevo");
  }

  private String acceptNotification(final String externalId) {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", "tenant-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of(
                    "externalId", externalId,
                    "channelType", "EMAIL",
                    "recipientId", "recipient-1",
                    "recipientAddress", "alice@example.com",
                    "subject", "Hola",
                    "body", "Contenido",
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

  private Message awaitDeadLetteredMessage(final String notificationId) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
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
  void startsUpWithoutCredentialsAndTheDisabledProviderRemainsRegistered() {
    final BrevoNotificationProvider provider =
        applicationContext.getBean(BrevoNotificationProvider.class);

    assertEquals(
        co.edu.uco.notification.core.domain.valueobject.ProviderId.of("brevo"),
        provider.providerId());
  }

  @Test
  void aNotificationRoutedToADisabledProviderIsNeverDeliveredNorLostAndLeavesATrace() {
    final String notificationId = acceptNotification("brevo-disabled-1");
    final Message deadLettered = awaitDeadLetteredMessage(notificationId);

    assertNotNull(deadLettered, "el mensaje debe terminar en la cola de mensajes muertos");
    final Object cause =
        deadLettered
            .getMessageProperties()
            .getHeaders()
            .get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE);
    assertNotNull(cause, "el header de causa debe estar presente");
    assertTrue(cause.toString().contains("brevo"));
    assertTrue(cause.toString().contains("BREVO_API_KEY"));

    final Notification stored =
        notificationRepository.findById(NotificationId.of(notificationId)).block();
    assertNotNull(stored);
    assertTrue(stored.deliveryAttempts().isEmpty());
    assertEquals("PENDING", status(notificationId).get("status"));

    final String logs =
        logAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", String::concat);
    assertFalse(logs.contains("Hola"), "logs must not contain the subject");
    assertFalse(logs.contains("Contenido"), "logs must not contain the body");
    assertFalse(logs.contains("alice@example.com"), "logs must not contain the recipient address");
  }
}
