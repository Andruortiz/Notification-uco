package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.infrastructure.adapter.in.rabbit.ParametersEventPayload;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.LastKnownConfigurationDocument;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.FakeParametersSource;
import co.edu.uco.notification.infrastructure.support.FakeParametersSourceConfig;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
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
      "notification.parameters.poll-interval-ms=1000",
      "notification.parameters.events.exchange=parameters.events.e2e.exchange",
      "notification.parameters.events.routing-key=notification.configuration.changed",
      "notification.parameters.events.queue=notification.parameters.events.e2e.queue"
    })
@Testcontainers
@Import(FakeParametersSourceConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ParametersEventSubscriptionE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final String EXCHANGE = "parameters.events.e2e.exchange";
  private static final String ROUTING_KEY = "notification.configuration.changed";
  private static final String QUEUE = "notification.parameters.events.e2e.queue";
  private static final String DLQ = QUEUE + ".dlq";
  private static final String MAX_ATTEMPTS = "dispatch.max-attempts";
  private static final String REQUEUE_INTERVAL = "requeue.interval-ms";
  private static final String TENANT = "tenant-1";
  private static final Duration EVENT_ADOPTION_LIMIT = Duration.ofSeconds(5);
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration POLL_MARGIN = Duration.ofSeconds(2);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @LocalServerPort private int port;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitAdmin rabbitAdmin;

  @Autowired private ConfigurationView configurationView;

  @Autowired private FakeParametersSource fakeSource;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void captureLogs() {
    logAppender = new ListAppender<>();
    logAppender.list = new CopyOnWriteArrayList<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logAppender);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logAppender);
  }

  private WebTestClient client() {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(20))
        .build();
  }

  private JsonNode getConfiguration() {
    final byte[] body =
        client()
            .get()
            .uri("/configuration")
            .header("Authorization", TestTokens.bearer(TENANT, Role.ADMINISTRADOR))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBodyContent();
    try {
      return MAPPER.readTree(body);
    } catch (final java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static long parameterValue(final JsonNode configuration, final String key) {
    for (final JsonNode parameter : configuration.get("parameters")) {
      if (key.equals(parameter.get("key").asText())) {
        return parameter.get("currentValue").asLong();
      }
    }
    throw new IllegalStateException("parameter not exposed: " + key);
  }

  private void publish(final long version, final Map<String, Object> values) {
    try {
      publishRaw(MAPPER.writeValueAsBytes(new ParametersEventPayload(version, values)));
    } catch (final com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void publishRaw(final byte[] body) {
    final Message message =
        MessageBuilder.withBody(body).setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message);
  }

  private static Map<String, Object> values(final Object... pairs) {
    final Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put((String) pairs[index], pairs[index + 1]);
    }
    return values;
  }

  private int queueDepth(final String queue) {
    final Properties info = rabbitAdmin.getQueueProperties(queue);
    return info == null ? -1 : (Integer) info.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
  }

  private int consumers(final String queue) {
    final Properties info = rabbitAdmin.getQueueProperties(queue);
    return info == null ? -1 : (Integer) info.get(RabbitAdmin.QUEUE_CONSUMER_COUNT);
  }

  private Document storedLastKnown() {
    return mongoTemplate
        .findById(
            LastKnownConfigurationDocument.CURRENT_ID,
            Document.class,
            LastKnownConfigurationDocument.COLLECTION)
        .block();
  }

  private String logLines(final String marker) {
    return String.join(
        "\n",
        logAppender.list.stream()
            .map(LogLines::render)
            .filter(line -> line.contains(marker))
            .toList());
  }

  private static boolean await(final BooleanSupplier condition, final Duration limit) {
    final Instant deadline = Instant.now().plus(limit);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return true;
      }
      pause();
    }
    return condition.getAsBoolean();
  }

  private static void pause() {
    try {
      Thread.sleep(50);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Test
  @Order(1)
  void theSubscriptionIsDeclaredWithItsDeadLetterQueueAndAConsumer() {
    assertTrue(await(() -> consumers(QUEUE) == 1, WAIT), "the listener never started consuming");
    assertTrue(queueDepth(DLQ) >= 0, "the dead-letter queue was not declared");
  }

  @Test
  @Order(2)
  void anEventIsAdoptedWithinFiveSecondsWithoutAnyHttpStateAndPersistedAsLastKnown() {
    assertEquals(0, configurationView.snapshot().version());

    final Instant started = Instant.now();
    publish(1, values(MAX_ATTEMPTS, 4, REQUEUE_INTERVAL, 20_000));
    assertTrue(
        await(() -> configurationView.snapshot().version() == 1, EVENT_ADOPTION_LIMIT),
        "the event was never adopted");
    final Duration elapsed = Duration.between(started, Instant.now());

    assertTrue(elapsed.compareTo(EVENT_ADOPTION_LIMIT) <= 0, "adoption took " + elapsed);
    final ConfigurationSnapshot snapshot = configurationView.snapshot();
    assertEquals(ConfigurationSource.PARAMETERS, snapshot.source());
    assertEquals(4, snapshot.dispatchMaxAttempts());

    final JsonNode configuration = getConfiguration();
    assertEquals(1, configuration.get("version").asLong());
    assertEquals("PARAMETERS", configuration.get("source").asText());
    assertEquals(4, parameterValue(configuration, MAX_ATTEMPTS));
    assertEquals(20_000, parameterValue(configuration, REQUEUE_INTERVAL));

    assertTrue(
        await(() -> storedLastKnown() != null, Duration.ofSeconds(10)),
        "the last known configuration was never persisted");
    assertEquals(1L, ((Number) storedLastKnown().get("version")).longValue());

    final String applied = logLines("CONFIG_APPLIED");
    assertTrue(applied.contains("param-"), applied);
    assertTrue(applied.contains("transport=event"), applied);
    assertTrue(await(() -> queueDepth(QUEUE) == 0, WAIT), "the message was left unacknowledged");
  }

  @Test
  @Order(3)
  void aStaleAndADuplicatedVersionAreIgnoredAndTheNextValidOneStillApplies() {
    publish(1, values(MAX_ATTEMPTS, 9));
    publish(0, values(MAX_ATTEMPTS, 8));
    publish(2, values(MAX_ATTEMPTS, 6));

    assertTrue(
        await(() -> configurationView.snapshot().version() == 2, WAIT),
        "the valid version 2 was never adopted");

    assertEquals(6, configurationView.snapshot().dispatchMaxAttempts());
    assertTrue(logLines("CONFIG_IGNORED").contains("param-"), logLines("CONFIG_IGNORED"));
    assertTrue(await(() -> queueDepth(QUEUE) == 0, WAIT));
    assertEquals(0, queueDepth(DLQ));
  }

  @Test
  @Order(4)
  void unreadableMessagesGoToTheDeadLetterQueueAndDoNotBlockTheNextValidOne() {
    final int before = queueDepth(DLQ);

    publishRaw("this is not json".getBytes(StandardCharsets.UTF_8));
    publishRaw("{\"values\":{\"dispatch.max-attempts\":3}}".getBytes(StandardCharsets.UTF_8));
    publish(3, values(MAX_ATTEMPTS, 5));

    assertTrue(
        await(() -> configurationView.snapshot().version() == 3, WAIT),
        "the valid version 3 was never adopted after the unreadable messages");
    assertEquals(5, configurationView.snapshot().dispatchMaxAttempts());
    assertTrue(
        await(() -> queueDepth(DLQ) == before + 2, WAIT),
        "the unreadable messages did not reach the dead-letter queue");
    assertTrue(await(() -> queueDepth(QUEUE) == 0, WAIT));
    assertEquals(1, consumers(QUEUE));
    assertFalse(logLines("NTF-3012").isEmpty());
  }

  @Test
  @Order(5)
  void aRejectedChangeIsAcknowledgedKeepsTheVersionAndLogsNoRejectedValue() {
    final int dlqBefore = queueDepth(DLQ);

    publish(4, values(MAX_ATTEMPTS, 98_765));
    publish(5, values(MAX_ATTEMPTS, 7));

    assertTrue(
        await(() -> configurationView.snapshot().version() == 5, WAIT),
        "the valid version 5 was never adopted");
    assertEquals(7, configurationView.snapshot().dispatchMaxAttempts());

    final String rejected = logLines("CONFIG_REJECTED");
    assertFalse(rejected.isEmpty(), "no CONFIG_REJECTED event was logged");
    assertTrue(rejected.contains("param-"), rejected);
    assertFalse(rejected.contains("98765"), rejected);
    assertFalse(rejected.contains(TestTokens.SECRET), rejected);
    assertEquals(dlqBefore, queueDepth(DLQ));
    assertTrue(await(() -> queueDepth(QUEUE) == 0, WAIT));
  }

  @Test
  @Order(6)
  void aChangeAvailableOnlyOverHttpIsAdoptedByThePollingAsReconciliation() {
    final long next = configurationView.snapshot().version() + 1;

    final Instant started = Instant.now();
    fakeSource.publish(next, values(MAX_ATTEMPTS, 11));
    assertTrue(
        await(() -> configurationView.snapshot().version() == next, WAIT),
        "the polling never adopted the change");
    final Duration elapsed = Duration.between(started, Instant.now());

    assertEquals(11, configurationView.snapshot().dispatchMaxAttempts());
    assertTrue(elapsed.compareTo(POLL_INTERVAL.plus(POLL_MARGIN)) < 0, "adoption took " + elapsed);
    assertNotNull(configurationView.snapshot().adoptedAt());
  }

  @Test
  @Order(7)
  void anEventForTheVersionThePollingAlreadyAdoptedIsIgnored() {
    final long adopted = configurationView.snapshot().version();

    publish(adopted, values(MAX_ATTEMPTS, 2));
    publish(adopted + 1, values(MAX_ATTEMPTS, 12));

    assertTrue(
        await(() -> configurationView.snapshot().version() == adopted + 1, WAIT),
        "the control event was never adopted");
    assertEquals(12, configurationView.snapshot().dispatchMaxAttempts());
  }
}
