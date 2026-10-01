package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.config.SanitizingThrowableConverter;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
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
      "notification.catalog.refresh-interval-ms=500"
    })
@Testcontainers
class LogCorrelationE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TERMINAL_STATUS_TIMEOUT = Duration.ofSeconds(25);
  private static final Duration LOG_PROPAGATION_TIMEOUT = Duration.ofSeconds(10);
  private static final String SENTINEL_ADDRESS = "sentinel.person@example.com";
  private static final String SENTINEL_BODY = "SENTINEL-BODY-5e1c7a";
  private static final String SENTINEL_SUBJECT = "SENTINEL-SUBJECT-91ab3d";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  private WebTestClient webTestClient;
  private ByteArrayOutputStream captured;
  private OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    awaitRoute("EMAIL");
    attachJsonAppender();
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(appender);
    appender.stop();
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private void attachJsonAppender() {
    final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    captured = new ByteArrayOutputStream();
    final LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.addIncludeMdcKeyName("correlationId");
    encoder.addIncludeMdcKeyName("tenantId");
    encoder.addIncludeMdcKeyName("notificationId");
    encoder.addIncludeMdcKeyName("traceparent");
    final SanitizingThrowableConverter converter = new SanitizingThrowableConverter();
    converter.setContext(context);
    converter.start();
    encoder.setThrowableConverter(converter);
    encoder.start();
    appender = new OutputStreamAppender<>();
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.setOutputStream(captured);
    appender.start();
    rootLogger().addAppender(appender);
  }

  private void awaitRoute(final String channelType) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      if (channelCatalogPort
          .findActiveRoute(ChannelType.of(channelType), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
    }
    throw new IllegalStateException("channel " + channelType + " never became active");
  }

  private static Map<String, Object> body(final String externalId) {
    return Map.of(
        "externalId", externalId,
        "channelType", "EMAIL",
        "recipientId", "recipient-1",
        "recipientAddress", SENTINEL_ADDRESS,
        "subject", SENTINEL_SUBJECT,
        "body", SENTINEL_BODY,
        "priority", "NORMAL");
  }

  private String accept(final String tenant, final String correlationId, final String externalId) {
    final WebTestClient.ResponseSpec response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(tenant))
            .header("X-Correlation-Id", correlationId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(externalId))
            .exchange()
            .expectStatus()
            .isEqualTo(202)
            .expectHeader()
            .valueEquals("X-Correlation-Id", correlationId);
    final Map<String, Object> accepted =
        response
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    return (String) accepted.get("notificationId");
  }

  private Map<String, Object> status(final String tenant, final String notificationId) {
    return webTestClient
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("Authorization", TestTokens.bearer(tenant))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private Map<String, Object> awaitDelivered(final String tenant, final String notificationId) {
    final Instant started = Instant.now();
    Map<String, Object> current = status(tenant, notificationId);
    while (Duration.between(started, Instant.now()).compareTo(TERMINAL_STATUS_TIMEOUT) < 0
        && !"DELIVERED".equals(String.valueOf(current.get("status")))) {
      current = status(tenant, notificationId);
    }
    assertTrue(
        Duration.between(started, Instant.now()).compareTo(TERMINAL_STATUS_TIMEOUT) <= 0,
        "the terminal status must be reached within " + TERMINAL_STATUS_TIMEOUT);
    assertEquals("DELIVERED", current.get("status"));
    return current;
  }

  private List<JsonNode> entries() {
    final String output = captured.toString(StandardCharsets.UTF_8);
    return output
        .lines()
        .filter(line -> !line.isBlank())
        .map(
            line -> {
              try {
                return MAPPER.readTree(line);
              } catch (final Exception e) {
                throw new AssertionError("log line is not valid JSON: " + line, e);
              }
            })
        .toList();
  }

  private List<JsonNode> entriesFor(final String notificationId) {
    return entries().stream()
        .filter(entry -> notificationId.equals(entry.path("notificationId").asText(null)))
        .toList();
  }

  @Test
  void everyEntryOfTheJourneyCarriesTheCorrelationIdTheRequestSent() {
    final String correlationId = "e2e-corr-journey";

    final String notificationId = accept("tenant-log-1", correlationId, "log-journey");
    final Map<String, Object> delivered = awaitDelivered("tenant-log-1", notificationId);

    assertEquals(correlationId, delivered.get("correlationId"));
    final Set<String> events =
        awaitEvents(
            notificationId,
            Set.of("NotificationAccepted", "NotificationQueued", "NotificationDelivered"));
    final List<JsonNode> journey = entriesFor(notificationId);
    assertFalse(journey.isEmpty());
    journey.forEach(entry -> assertEquals(correlationId, entry.path("correlationId").asText()));
    assertTrue(events.containsAll(Set.of("NotificationAccepted", "NotificationQueued")));
    assertTrue(events.contains("NotificationDelivered"), events.toString());
  }

  private Set<String> awaitEvents(final String notificationId, final Set<String> expected) {
    final Instant started = Instant.now();
    Set<String> events = eventsOf(notificationId);
    while (!events.containsAll(expected)
        && Duration.between(started, Instant.now()).compareTo(LOG_PROPAGATION_TIMEOUT) < 0) {
      events = eventsOf(notificationId);
    }
    assertTrue(
        Duration.between(started, Instant.now()).compareTo(LOG_PROPAGATION_TIMEOUT) <= 0,
        "the lifecycle logs must appear within " + LOG_PROPAGATION_TIMEOUT);
    return events;
  }

  private Set<String> eventsOf(final String notificationId) {
    return Set.copyOf(
        entriesFor(notificationId).stream()
            .map(entry -> entry.path("event").asText(""))
            .filter(event -> !event.isEmpty())
            .toList());
  }

  @Test
  void concurrentTenantsNeverSeeEachOthersIdentifiersAndBothAppear() {
    final CompletableFuture<String> first =
        CompletableFuture.supplyAsync(
            () -> accept("tenant-log-a", "e2e-corr-a", "log-concurrent-a"));
    final CompletableFuture<String> second =
        CompletableFuture.supplyAsync(
            () -> accept("tenant-log-b", "e2e-corr-b", "log-concurrent-b"));
    final String idA = first.join();
    final String idB = second.join();
    awaitDelivered("tenant-log-a", idA);
    awaitDelivered("tenant-log-b", idB);

    final List<JsonNode> entriesA = entriesFor(idA);
    final List<JsonNode> entriesB = entriesFor(idB);
    assertFalse(entriesA.isEmpty(), "positive control: tenant a must appear in the logs");
    assertFalse(entriesB.isEmpty(), "positive control: tenant b must appear in the logs");
    entriesA.forEach(
        entry -> {
          assertEquals("e2e-corr-a", entry.path("correlationId").asText());
          assertEquals("tenant-log-a", entry.path("tenantId").asText());
        });
    entriesB.forEach(
        entry -> {
          assertEquals("e2e-corr-b", entry.path("correlationId").asText());
          assertEquals("tenant-log-b", entry.path("tenantId").asText());
        });
  }

  @Test
  void sentinelRecipientAndContentNeverReachTheLogOutputAndEveryLineIsJson() {
    final String notificationId = accept("tenant-log-s", "e2e-corr-sentinel", "log-sentinel");
    awaitDelivered("tenant-log-s", notificationId);

    final String output = captured.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains(SENTINEL_ADDRESS));
    assertFalse(output.contains(SENTINEL_BODY));
    assertFalse(output.contains(SENTINEL_SUBJECT));
    assertFalse(entries().isEmpty());
  }

  @Test
  void errorsAndRejectionsReturnTheCorrelationIdInHeaderAndBody() {
    webTestClient
        .get()
        .uri("/notifications/{id}", "00000000-0000-0000-0000-000000000000")
        .header("Authorization", TestTokens.bearer("tenant-log-e"))
        .header("X-Correlation-Id", "e2e-corr-404")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .valueEquals("X-Correlation-Id", "e2e-corr-404")
        .expectBody()
        .jsonPath("$.correlationId")
        .isEqualTo("e2e-corr-404");

    webTestClient
        .get()
        .uri("/notifications/{id}", "00000000-0000-0000-0000-000000000000")
        .header("X-Correlation-Id", "e2e-corr-401")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals("X-Correlation-Id", "e2e-corr-401")
        .expectBody()
        .jsonPath("$.correlationId")
        .isEqualTo("e2e-corr-401");

    final boolean rejectionLogged =
        entries().stream()
            .anyMatch(
                entry ->
                    "e2e-corr-401".equals(entry.path("correlationId").asText())
                        && "WARN".equals(entry.path("level").asText()));
    assertTrue(rejectionLogged);
  }

  @Test
  void aMissingOrInvalidCorrelationIdIsReplacedByAGeneratedOneEchoedInTheResponse() {
    final String echoed =
        webTestClient
            .get()
            .uri("/notifications/{id}", "00000000-0000-0000-0000-000000000000")
            .header("Authorization", TestTokens.bearer("tenant-log-g"))
            .header("X-Correlation-Id", "bad id with spaces")
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst("X-Correlation-Id");

    assertNotNull(echoed);
    assertTrue(echoed.matches("[A-Za-z0-9._-]{1,64}"));
  }
}
