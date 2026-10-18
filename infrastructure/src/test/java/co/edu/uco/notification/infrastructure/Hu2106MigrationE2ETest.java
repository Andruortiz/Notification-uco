package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.infrastructure.support.NotificationFlow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class Hu2106MigrationE2ETest {

  private static final Duration LIMIT = Duration.ofSeconds(30);
  private static final String TENANT = "tenant-a";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int apiPort;
  @LocalManagementPort private int managementPort;
  @Autowired private ConnectionFactory connectionFactory;
  @Autowired private RabbitTopologyProperties topology;
  @Autowired private ObjectMapper mapper;
  @Autowired private ChannelCatalogPort channelCatalogPort;

  private String eventsQueue;

  @BeforeEach
  void bindAnEventsQueueOnceTheChannelIsActive() throws InterruptedException {
    final Instant deadline = Instant.now().plus(LIMIT);
    boolean active = false;
    while (!active && Instant.now().isBefore(deadline)) {
      active =
          channelCatalogPort
              .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("readiness-check"))
              .blockOptional()
              .isPresent();
      if (!active) {
        Thread.sleep(200);
      }
    }
    assertTrue(active, "channel EMAIL never became active");
    final RabbitAdmin admin = new RabbitAdmin(connectionFactory);
    final Queue queue = new Queue("hu2106-events-" + UUID.randomUUID(), false, false, false);
    assertNotNull(admin.declareQueue(queue), "queue declaration returned nothing");
    admin.declareBinding(
        BindingBuilder.bind(queue).to(new FanoutExchange(topology.eventsExchange())));
    eventsQueue = queue.getName();
  }

  @Test
  void aNotificationIsAcceptedDeliveredPublishedAndObservableWithTheSameShapeAsBefore()
      throws Exception {
    final NotificationFlow flow = new NotificationFlow(NotificationFlow.client(apiPort, LIMIT));

    final String id = flow.accept(TENANT, "migration-1", "corr-migration-1", null);
    flow.awaitStatus(TENANT, id, "DELIVERED", LIMIT);

    final RabbitTemplate template = new RabbitTemplate(connectionFactory);
    final List<JsonNode> events = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      final Message message = template.receive(eventsQueue, 3_000);
      if (message == null) {
        break;
      }
      events.add(mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8)));
    }
    final JsonNode delivered =
        events.stream()
            .filter(event -> id.equals(event.path("notificationId").path("value").asString()))
            .reduce((first, second) -> second)
            .orElse(null);
    assertNotNull(delivered, events.toString());
    assertEquals(
        List.of("notificationId", "tenantId", "correlationId", "occurredOn"),
        List.copyOf(delivered.propertyNames()));
    assertEquals(TENANT, delivered.path("tenantId").path("value").asString());

    final WebTestClient management =
        WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
    final String scrape =
        management
            .get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertTrue(scrape.contains("http_server_requests_seconds_bucket"), "histogram");
    assertTrue(scrape.contains("notification_configuration_version"), "business gauge");

    management
        .get()
        .uri("/actuator/health/readiness")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
  }
}
