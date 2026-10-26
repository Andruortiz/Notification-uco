package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "provider-health.grafana-url=http://grafana.test/",
      "provider-health.timeout=2s"
    })
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProviderHealthE2ETest {

  private static final String SECRET_MESSAGE = "juan.perez@example.com token=abc123secret";
  private static final long NOW = Instant.now().getEpochSecond();
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final HttpServer PROMETHEUS = startPrometheus();
  private static final String PROMETHEUS_URL = "http://localhost:" + port(PROMETHEUS);

  @LocalServerPort private int port;

  private WebTestClient webTestClient;

  @DynamicPropertySource
  static void prometheusUrl(final DynamicPropertyRegistry registry) {
    registry.add("provider-health.prometheus-url", () -> PROMETHEUS_URL);
  }

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
  }

  @AfterAll
  static void stopPrometheus() {
    PROMETHEUS.stop(0);
  }

  @Test
  @Order(1)
  void operatorSeesStatusRateLatencyAndLastErrorWithinTwoSeconds() throws Exception {
    final long start = System.nanoTime();
    final byte[] body =
        get("tenant-a", Role.OPERADOR)
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) <= 0, "took " + elapsed);
    final JsonNode json = MAPPER.readTree(body);
    assertEquals("http://grafana.test/d/notification-service", json.get("grafanaUrl").asString());
    assertEquals(300, json.get("windowSeconds").asInt());
    assertNotNull(json.get("generatedAt"));
    assertEquals(3, json.get("providers").size());

    final JsonNode brevo = provider(json, "brevo", "EMAIL");
    assertEquals("DEGRADED", brevo.get("status").asString());
    assertEquals(0.9, brevo.get("successRate").asDouble(), 1e-9);
    assertEquals(100, brevo.get("sampleCount").asInt());
    assertEquals(250, brevo.get("latencyP95Millis").asInt());
    assertEquals("recoverable", brevo.get("lastError").get("result").asString());

    final JsonNode twilio = provider(json, "twilio", "SMS");
    assertEquals("DOWN", twilio.get("status").asString());
    assertEquals("failed", twilio.get("lastError").get("result").asString());
    assertEquals(
        Instant.ofEpochSecond(NOW - 60).toString(), twilio.get("lastError").get("at").asString());

    final JsonNode fcm = provider(json, "fcm", "PUSH");
    assertEquals("UNKNOWN", fcm.get("status").asString());
    assertTrue(fcm.get("successRate").isNull());
    assertTrue(fcm.get("latencyP95Millis").isNull());
    assertTrue(fcm.get("lastError").isNull());
  }

  @Test
  @Order(2)
  void responseHasNoSensitiveTextAndDropsInvalidLabels() {
    final String text =
        new String(
            get("tenant-a", Role.OPERADOR)
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody(),
            StandardCharsets.UTF_8);

    assertFalse(text.contains("example.com"));
    assertFalse(text.contains("abc123secret"));
    assertFalse(text.contains("evil provider"));
    assertFalse(text.contains(PROMETHEUS_URL));
  }

  @Test
  @Order(3)
  void twoTenantsGetTheSameGlobalAnswer() {
    final String a = bodyOf(get("tenant-a", Role.OPERADOR));
    final String b = bodyOf(get("tenant-b", Role.ADMINISTRADOR_PLATAFORMA));

    assertEquals(
        a.replaceAll("\"generatedAt\":\"[^\"]*\"", ""),
        b.replaceAll("\"generatedAt\":\"[^\"]*\"", ""));
  }

  @Test
  @Order(4)
  void clientRoleIsForbidden() {
    get("tenant-a", Role.CLIENTE).expectStatus().isForbidden();
  }

  @Test
  @Order(5)
  void answers503WithInfrastructureErrorAndNoUrlWhenPrometheusIsDown() {
    get("tenant-a", Role.OPERADOR).expectStatus().isOk();
    PROMETHEUS.stop(0);

    final String text =
        new String(
            get("tenant-a", Role.OPERADOR)
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NTF-5000")
                .jsonPath("$.correlationId")
                .isNotEmpty()
                .returnResult()
                .getResponseBody(),
            StandardCharsets.UTF_8);

    assertFalse(text.contains(PROMETHEUS_URL));
    assertFalse(text.contains("localhost:" + port(PROMETHEUS)));
  }

  private WebTestClient.ResponseSpec get(final String tenant, final Role role) {
    return webTestClient
        .get()
        .uri("/provider-health")
        .header("Authorization", TestTokens.bearer(tenant, role))
        .exchange();
  }

  private static String bodyOf(final WebTestClient.ResponseSpec spec) {
    return new String(
        spec.expectStatus().isOk().expectBody().returnResult().getResponseBody(),
        StandardCharsets.UTF_8);
  }

  private static JsonNode provider(
      final JsonNode json, final String provider, final String channel) {
    for (final JsonNode node : json.get("providers")) {
      if (provider.equals(node.get("provider").asString())
          && channel.equals(node.get("channel").asString())) {
        return node;
      }
    }
    throw new AssertionError("missing " + provider + "/" + channel);
  }

  private static int port(final HttpServer server) {
    return server.getAddress().getPort();
  }

  private static HttpServer startPrometheus() {
    try {
      final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/api/v1/",
          exchange -> {
            final String path = exchange.getRequestURI().getPath();
            final String query = exchange.getRequestURI().getQuery();
            final String body =
                path.endsWith("query_range")
                    ? rangeBody()
                    : query.contains("histogram_quantile") ? latencyBody() : countsBody();
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sample(
      final String provider, final String channel, final String result, final String value) {
    return "{\"metric\":{\"provider\":\""
        + provider
        + "\",\"channel\":\""
        + channel
        + "\",\"result\":\""
        + result
        + "\"},\"value\":["
        + NOW
        + ",\""
        + value
        + "\"]}";
  }

  private static String countsBody() {
    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
        + sample("brevo", "EMAIL", "delivered", "90")
        + ","
        + sample("brevo", "EMAIL", "failed", "10")
        + ","
        + sample("brevo", "EMAIL", "recoverable", "4")
        + ","
        + sample("twilio", "SMS", "delivered", "1")
        + ","
        + sample("twilio", "SMS", "failed", "19")
        + ","
        + sample("fcm", "PUSH", "delivered", "2")
        + ","
        + sample("evil provider", "EMAIL", "delivered", "50")
        + "]}}";
  }

  private static String latencyBody() {
    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
        + "{\"metric\":{\"provider\":\"brevo\"},\"value\":["
        + NOW
        + ",\"0.25\"]},"
        + "{\"metric\":{\"provider\":\"twilio\"},\"value\":["
        + NOW
        + ",\"NaN\"]}]}}";
  }

  private static String rangeBody() {
    return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
        + series("brevo", "EMAIL", "recoverable", NOW - 120)
        + ","
        + series("twilio", "SMS", "failed", NOW - 60)
        + ","
        + series("twilio", "SMS", "recoverable", NOW - 180)
        + "]}}";
  }

  private static String series(
      final String provider, final String channel, final String result, final long at) {
    return "{\"metric\":{\"provider\":\""
        + provider
        + "\",\"channel\":\""
        + channel
        + "\",\"result\":\""
        + result
        + "\"},\"values\":[["
        + (at - 60)
        + ",\"0\"],["
        + at
        + ",\"1.5\"]]}";
  }
}
