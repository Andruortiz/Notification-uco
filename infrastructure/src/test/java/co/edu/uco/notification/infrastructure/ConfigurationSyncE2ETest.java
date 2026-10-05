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
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.LastKnownConfigurationDocument;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.FakeParametersSource;
import co.edu.uco.notification.infrastructure.support.FakeParametersSourceConfig;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
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
      "notification.parameters.poll-interval-ms=1000"
    })
@Testcontainers
@Import(FakeParametersSourceConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigurationSyncE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final Duration SYNC_WAIT = Duration.ofSeconds(15);
  private static final String BREVO_TIMEOUT = "provider.brevo.timeout-ms";
  private static final String REQUEUE_INTERVAL = "requeue.interval-ms";
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration ADOPTION_MARGIN = Duration.ofSeconds(2);

  @LocalServerPort private int port;

  @Autowired private FakeParametersSource fakeSource;

  @Autowired private ConfigurationView configurationView;

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

  private static WebTestClient clientFor(final int serverPort) {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + serverPort)
        .responseTimeout(Duration.ofSeconds(20))
        .build();
  }

  private static String acceptNotification(final WebTestClient client, final String externalId) {
    final Map<String, Object> response =
        client
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer("tenant-1"))
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

  private static Map<String, Object> awaitDelivered(
      final WebTestClient client, final String notificationId) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    Map<String, Object> current = status(client, notificationId);
    while (Instant.now().isBefore(deadline)
        && !"DELIVERED".equals(String.valueOf(current.get("status")))) {
      sleep(200);
      current = status(client, notificationId);
    }
    return current;
  }

  private static Map<String, Object> status(
      final WebTestClient client, final String notificationId) {
    return client
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static boolean awaitCondition(final BooleanSupplier condition, final Duration limit) {
    final Instant deadline = Instant.now().plus(limit);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return true;
      }
      sleep(100);
    }
    return condition.getAsBoolean();
  }

  private static void awaitCycles(final FakeParametersSource source, final int cycles) {
    final int target = source.calls() + cycles;
    assertTrue(
        awaitCondition(() -> source.calls() >= target, SYNC_WAIT),
        "the polling did not run " + cycles + " more cycles");
  }

  private Document storedLastKnown() {
    return mongoTemplate
        .findById(
            LastKnownConfigurationDocument.CURRENT_ID,
            Document.class,
            LastKnownConfigurationDocument.COLLECTION)
        .block();
  }

  private static Map<String, Object> values(final Object... pairs) {
    final Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put((String) pairs[index], pairs[index + 1]);
    }
    return values;
  }

  @Test
  @Order(1)
  void startsOnDefaultsAndDispatchesWhileTheSourceIsDownAndKeepsPolling() {
    awaitCycles(fakeSource, 2);

    final ConfigurationSnapshot snapshot = configurationView.snapshot();
    assertEquals(ConfigurationSource.DEFAULTS, snapshot.source());
    assertEquals(0, snapshot.version());
    assertEquals(3, snapshot.dispatchMaxAttempts());

    final WebTestClient client = clientFor(port);
    final Map<String, Object> delivered =
        awaitDelivered(client, acceptNotification(client, "sync-defaults-1"));

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("simulated", delivered.get("providerId"));
  }

  @Test
  @Order(2)
  void resumesSynchronizationWithoutRestartWhenTheSourceReturnsAndPersistsTheLastKnown() {
    fakeSource.publish(1, values("dispatch.max-attempts", 4, BREVO_TIMEOUT, 7000));

    assertTrue(
        awaitCondition(() -> configurationView.snapshot().version() == 1, SYNC_WAIT),
        "the published version was never adopted");

    final ConfigurationSnapshot snapshot = configurationView.snapshot();
    assertEquals(ConfigurationSource.PARAMETERS, snapshot.source());
    assertEquals(4, snapshot.dispatchMaxAttempts());
    assertEquals(7000, snapshot.providerTimeoutMs("brevo"));

    assertTrue(
        awaitCondition(() -> storedLastKnown() != null, Duration.ofSeconds(10)),
        "the last known configuration was never persisted");
    assertEquals(1L, ((Number) storedLastKnown().get("version")).longValue());

    final WebTestClient client = clientFor(port);
    final Map<String, Object> delivered =
        awaitDelivered(client, acceptNotification(client, "sync-resumed-1"));
    assertEquals("DELIVERED", delivered.get("status"));
  }

  @Test
  @Order(3)
  void restartsOnTheLastKnownConfigurationWhileTheSourceIsDownAndDispatches() {
    assertNotNull(storedLastKnown());

    try (ConfigurableApplicationContext second = startSecondInstance()) {
      final ConfigurationSnapshot snapshot = second.getBean(ConfigurationView.class).snapshot();
      assertEquals(ConfigurationSource.LAST_KNOWN, snapshot.source());
      assertEquals(1, snapshot.version());
      assertEquals(4, snapshot.dispatchMaxAttempts());
      assertEquals(7000, snapshot.providerTimeoutMs("brevo"));

      final FakeParametersSource secondSource = second.getBean(FakeParametersSource.class);
      awaitCycles(secondSource, 2);
      assertEquals(
          ConfigurationSource.LAST_KNOWN,
          second.getBean(ConfigurationView.class).snapshot().source());

      final WebTestClient client =
          clientFor(
              Integer.parseInt(second.getEnvironment().getProperty("local.server.port", "0")));
      final Map<String, Object> delivered =
          awaitDelivered(client, acceptNotification(client, "sync-last-known-1"));
      assertEquals("DELIVERED", delivered.get("status"));

      secondSource.publish(2, values("dispatch.max-attempts", 6));
      assertTrue(
          awaitCondition(
              () -> second.getBean(ConfigurationView.class).snapshot().version() == 2, SYNC_WAIT),
          "the second instance did not resume the synchronization");
      assertEquals(6, second.getBean(ConfigurationView.class).snapshot().dispatchMaxAttempts());
    }
  }

  @Test
  @Order(4)
  void aCrossParameterRuleViolationIsRejectedWholeAndKeepsTheCurrentVersion() {
    final long before = configurationView.snapshot().version();
    assertEquals(1, before);
    final Instant beforeAdoption = configurationView.snapshot().adoptedAt();

    fakeSource.publish(2, values("dispatch.max-attempts", 5, BREVO_TIMEOUT, 40_000));
    awaitCycles(fakeSource, 3);

    final ConfigurationSnapshot snapshot = configurationView.snapshot();
    assertEquals(1, snapshot.version());
    assertEquals(4, snapshot.dispatchMaxAttempts());
    assertEquals(7000, snapshot.providerTimeoutMs("brevo"));
    assertEquals(beforeAdoption, snapshot.adoptedAt());
  }

  @Test
  @Order(5)
  void theRejectionIsLoggedWithTheReasonAndACorrelationIdWithoutSensitiveValues() {
    awaitCycles(fakeSource, 1);

    assertTrue(
        awaitCondition(
            () ->
                logAppender.list.stream()
                    .map(LogLines::render)
                    .anyMatch(line -> line.contains("CONFIG_REJECTED")),
            SYNC_WAIT),
        "no CONFIG_REJECTED event was logged");

    final List<String> lines =
        logAppender.list.stream()
            .map(LogLines::render)
            .filter(line -> line.contains("CONFIG_REJECTED"))
            .toList();
    final String joined = String.join("\n", lines);
    assertTrue(joined.contains("ProviderTimeoutBelowRequeueIntervalRule"), joined);
    assertTrue(joined.contains("param-"), joined);
    assertFalse(joined.contains(TestTokens.SECRET), joined);
    assertFalse(joined.contains("Bearer"), joined);
  }

  @Test
  @Order(6)
  void anUnknownKeyIsRejectedWholeAndKeepsTheCurrentVersion() {
    fakeSource.publish(2, values("dispatch.max-attempts", 5, "unknown.parameter", 1));
    awaitCycles(fakeSource, 3);

    final ConfigurationSnapshot snapshot = configurationView.snapshot();
    assertEquals(1, snapshot.version());
    assertEquals(4, snapshot.dispatchMaxAttempts());
  }

  @Test
  @Order(7)
  void aValidNewerVersionIsAppliedAfterTheRejectionsControlForTheRejectedOnes() {
    fakeSource.publish(3, values("dispatch.max-attempts", 5, BREVO_TIMEOUT, 8000));

    assertTrue(
        awaitCondition(() -> configurationView.snapshot().version() == 3, SYNC_WAIT),
        "the valid version 3 was never adopted");

    final ConfigurationSnapshot snapshot = configurationView.snapshot();
    assertEquals(5, snapshot.dispatchMaxAttempts());
    assertEquals(8000, snapshot.providerTimeoutMs("brevo"));
  }

  @Test
  @Order(8)
  void aRequeueIntervalChangeGovernsWithinOnePollingIntervalPlusMargin() {
    assertEquals(30_000, configurationView.snapshot().requeueIntervalMs());

    final Duration elapsed =
        fakeSource.publishNextAndAwaitAdoption(
            configurationView, values(REQUEUE_INTERVAL, 20_000), SYNC_WAIT);

    assertEquals(20_000, configurationView.snapshot().requeueIntervalMs());
    assertEquals(ConfigurationSource.PARAMETERS, configurationView.snapshot().source());
    assertTrue(
        elapsed.compareTo(POLL_INTERVAL.plus(ADOPTION_MARGIN)) < 0, "adoption took " + elapsed);
  }

  private ConfigurableApplicationContext startSecondInstance() {
    return new SpringApplicationBuilder(
            NotificationServiceApplication.class, FakeParametersSourceConfig.class)
        .web(WebApplicationType.REACTIVE)
        .properties(
            "server.port=0",
            "MONGO_USERNAME=test",
            "MONGO_PASSWORD=test",
            "spring.data.mongodb.uri=" + MONGO.getReplicaSetUrl(),
            "spring.rabbitmq.host=" + RABBIT.getHost(),
            "spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
            "spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
            "spring.rabbitmq.password=" + RABBIT.getAdminPassword(),
            "notification.parameters.poll-interval-ms=1000")
        .run();
  }
}
