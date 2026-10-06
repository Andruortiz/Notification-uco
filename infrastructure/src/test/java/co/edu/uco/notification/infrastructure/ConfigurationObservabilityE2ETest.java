package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome.Status;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.in.ApplyConfigurationChangeUseCase;
import co.edu.uco.notification.infrastructure.adapter.in.scheduler.ConfigurationEventLogger;
import co.edu.uco.notification.infrastructure.config.ConfigurationConfig;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
      "notification.provider.brevo.api-key=" + ConfigurationObservabilityE2ETest.BREVO_KEY,
      "notification.provider.brevo.sender-email=origen@example.com",
      "notification.provider.brevo.base-url=" + ConfigurationObservabilityE2ETest.BREVO_URL,
      "notification.provider.twilio.account-sid=AC00000000000000000000000000000000",
      "notification.provider.twilio.auth-token=" + ConfigurationObservabilityE2ETest.TWILIO_TOKEN,
      "notification.provider.twilio.from-number=+15005550006",
      "notification.provider.twilio.base-url=" + ConfigurationObservabilityE2ETest.TWILIO_URL,
      "notification.provider.fcm.credentials-json=",
      "notification.provider.fcm.credentials-file="
    })
@Testcontainers
class ConfigurationObservabilityE2ETest {

  static final String BREVO_KEY = "hu2-073-" + "brevo-key-" + "reconocible";
  static final String TWILIO_TOKEN = "hu2-073-" + "twilio-token-" + "reconocible";
  static final String BREVO_URL = "https://brevo-" + "interno.example.test";
  static final String TWILIO_URL = "https://twilio-" + "interno.example.test";

  private static final String TENANT = "tenant-configuracion";
  private static final Duration RESPONSE_LIMIT = Duration.ofSeconds(5);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ApplyConfigurationChangeUseCase applyUseCase;

  @Autowired private ConfigurationEventLogger eventLogger;

  @Autowired private ParameterRegistry registry;

  @Autowired private MeterRegistry meterRegistry;

  private WebTestClient webTestClient;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void bindClientAndCaptureLogs() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(ConfigurationEventLogger.class)).addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(ConfigurationEventLogger.class)).detachAppender(logs);
  }

  private JsonNode getConfiguration(final Role role) {
    final byte[] body =
        webTestClient
            .get()
            .uri("/configuration")
            .header("Authorization", TestTokens.bearer(TENANT, role))
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

  private List<String> lines() {
    return logs.list.stream().map(LogLines::render).toList();
  }

  private List<String> linesOf(final String event) {
    return lines().stream().filter(line -> line.contains("event=" + event)).toList();
  }

  private double gauge(final String source) {
    return meterRegistry
        .get(ConfigurationConfig.VERSION_METRIC)
        .tag("source", source)
        .gauge()
        .value();
  }

  @Test
  void administradorGetsTheRegistryWithEveryGroupAndNoCredentialsOrBaseAddresses() {
    final long started = System.nanoTime();
    final JsonNode body = getConfiguration(Role.ADMINISTRADOR);
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertTrue(elapsed.compareTo(RESPONSE_LIMIT) < 0);
    final Set<String> keys = new HashSet<>();
    body.get("parameters").forEach(parameter -> keys.add(parameter.get("key").asText()));
    final Set<String> expected = new HashSet<>();
    registry.descriptors().stream().map(ParameterDescriptor::key).forEach(expected::add);
    assertEquals(expected, keys);
    assertTrue(keys.contains("dispatch.max-attempts"));
    assertTrue(keys.contains("requeue.interval-ms"));
    assertTrue(keys.contains("provider.brevo.timeout-ms"));
    assertTrue(keys.contains("provider.twilio.connect-timeout-ms"));
    final String raw = body.toString();
    for (final String secret : List.of(BREVO_KEY, TWILIO_TOKEN, BREVO_URL, TWILIO_URL)) {
      assertFalse(raw.contains(secret));
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"OPERADOR", "CLIENTE"})
  void rolesBelowAdministradorGetForbidden(final Role role) {
    webTestClient
        .get()
        .uri("/configuration")
        .header("Authorization", TestTokens.bearer(TENANT, role))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void aRequestWithoutATokenIsUnauthorized() {
    webTestClient.get().uri("/configuration").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void appliedAndRejectedChangesShowUpInTheEndpointTheLogAndTheMetric() {
    final long baseline = getConfiguration(Role.ADMINISTRADOR).get("version").asLong();
    final long applied = baseline + 1;
    final long rejected = baseline + 2;

    final Status appliedStatus =
        eventLogger
            .observe(applyUseCase.apply(change(applied, "dispatch.max-attempts", 6)))
            .contextWrite(c -> c.put("correlationId", "param-e2e-applied"))
            .block()
            .status();
    assertEquals(Status.APPLIED, appliedStatus);

    final JsonNode afterApplied = getConfiguration(Role.ADMINISTRADOR);
    assertEquals(applied, afterApplied.get("version").asLong());
    assertEquals("PARAMETERS", afterApplied.get("source").asText());
    assertEquals(6, currentValue(afterApplied, "dispatch.max-attempts"));
    assertEquals(applied, gauge("PARAMETERS"), 0.0);
    final List<String> appliedLines = linesOf("CONFIG_APPLIED");
    assertEquals(1, appliedLines.size());
    assertTrue(appliedLines.get(0).contains("correlationId=param-e2e-applied"));
    assertTrue(appliedLines.get(0).contains("newVersion=" + applied));

    final Status rejectedStatus =
        eventLogger
            .observe(applyUseCase.apply(change(rejected, "provider.brevo.timeout-ms", 40_000)))
            .contextWrite(c -> c.put("correlationId", "param-e2e-rejected"))
            .block()
            .status();
    assertEquals(Status.REJECTED, rejectedStatus);

    final JsonNode afterRejected = getConfiguration(Role.ADMINISTRADOR);
    assertEquals(applied, afterRejected.get("version").asLong());
    assertEquals(
        currentValue(afterApplied, "provider.brevo.timeout-ms"),
        currentValue(afterRejected, "provider.brevo.timeout-ms"));
    assertEquals(applied, gauge("PARAMETERS"), 0.0);
    final List<String> rejectedLines = linesOf("CONFIG_REJECTED");
    assertEquals(1, rejectedLines.size());
    assertTrue(rejectedLines.get(0).contains("correlationId=param-e2e-rejected"));
    assertTrue(rejectedLines.get(0).contains("provider.brevo.timeout-ms"));
    lines()
        .forEach(
            line -> {
              assertFalse(line.contains(BREVO_KEY));
              assertFalse(line.contains(TWILIO_TOKEN));
            });
  }

  private static ConfigurationChange change(
      final long version, final String key, final long value) {
    return new ConfigurationChange(version, Map.of(key, value));
  }

  private static long currentValue(final JsonNode body, final String key) {
    for (final JsonNode parameter : body.get("parameters")) {
      if (key.equals(parameter.get("key").asText())) {
        return parameter.get("currentValue").asLong();
      }
    }
    throw new IllegalStateException("missing " + key);
  }
}
