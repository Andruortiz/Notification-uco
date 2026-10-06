package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.channels.EMAIL.providers[0]=probe-mail",
      "notification.catalog.channels.SMS.providers[0]=probe-sms",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Import(MetricsE2ETest.ProbeSenderConfig.class)
@Testcontainers
class MetricsE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final Map<String, AtomicReference<AttemptResult>> RESULTS = new ConcurrentHashMap<>();

  @TestConfiguration
  static class ProbeSenderConfig {

    @Bean
    NotificationSenderPort probeMailSender() {
      return probe("probe-mail");
    }

    @Bean
    NotificationSenderPort probeSmsSender() {
      return probe("probe-sms");
    }

    private static NotificationSenderPort probe(final String id) {
      RESULTS.put(id, new AtomicReference<>(AttemptResult.ACCEPTED));
      return new NotificationSenderPort() {
        @Override
        public Mono<AttemptResult> send(final Notification notification) {
          return Mono.just(RESULTS.get(id).get());
        }

        @Override
        public ProviderId providerId() {
          return ProviderId.of(id);
        }

        @Override
        public Optional<String> disabledReason() {
          return Optional.empty();
        }

        @Override
        public boolean supportsAttachments() {
          return true;
        }
      };
    }
  }

  private record Series(String name, Map<String, String> labels, double value) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration LIMIT = Duration.ofSeconds(30);
  private static final String SENTINEL_RECIPIENT = "sentinel-recipient@secret.invalid";
  private static final String SENTINEL_CONTENT = "sentinel-content-4f2a9c";

  @LocalServerPort private int apiPort;

  @LocalManagementPort private int managementPort;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  private WebTestClient api;
  private WebTestClient management;

  @BeforeEach
  void setUp() {
    RESULTS.values().forEach(result -> result.set(AttemptResult.ACCEPTED));
    api =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + apiPort)
            .responseTimeout(LIMIT)
            .build();
    management =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + managementPort)
            .responseTimeout(LIMIT)
            .build();
    awaitRoute("EMAIL");
    awaitRoute("SMS");
  }

  private void awaitRoute(final String channel) {
    final Instant deadline = Instant.now().plus(LIMIT);
    while (Instant.now().isBefore(deadline)) {
      if (channelCatalogPort
          .findActiveRoute(ChannelType.of(channel), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
    }
    throw new IllegalStateException("channel " + channel + " never became active");
  }

  private String scrapeText() {
    return management
        .get()
        .uri("/actuator/prometheus")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private static List<Series> parse(final String text) {
    final List<Series> series = new ArrayList<>();
    for (final String line : text.split("\n")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      final int split = line.lastIndexOf(' ');
      final String head = line.substring(0, split);
      final double value = Double.parseDouble(line.substring(split + 1).trim());
      final int brace = head.indexOf('{');
      final Map<String, String> labels = new HashMap<>();
      final String name = brace < 0 ? head : head.substring(0, brace);
      if (brace >= 0) {
        final String inside = head.substring(brace + 1, head.lastIndexOf('}'));
        for (final String pair : inside.split(",(?=[A-Za-z_][A-Za-z0-9_]*=\")")) {
          final int eq = pair.indexOf('=');
          labels.put(pair.substring(0, eq), pair.substring(eq + 2, pair.length() - 1));
        }
      }
      series.add(new Series(name, labels, value));
    }
    return series;
  }

  private static double sum(
      final List<Series> series, final String name, final String... labelPairs) {
    return series.stream()
        .filter(entry -> entry.name().equals(name))
        .filter(
            entry -> {
              for (int i = 0; i < labelPairs.length; i += 2) {
                if (!labelPairs[i + 1].equals(entry.labels().get(labelPairs[i]))) {
                  return false;
                }
              }
              return true;
            })
        .mapToDouble(Series::value)
        .sum();
  }

  private Map<String, Object> body(
      final String externalId, final String channel, final String content) {
    return Map.of(
        "externalId",
        externalId,
        "channelType",
        channel,
        "recipientId",
        "recipient-1",
        "recipientAddress",
        channel.equals("SMS") ? "+573001234567" : SENTINEL_RECIPIENT,
        "subject",
        "Subject",
        "body",
        content,
        "priority",
        "NORMAL");
  }

  private String accept(
      final String tenant, final String externalId, final String channel, final String content)
      throws Exception {
    final EntityExchangeResult<byte[]> result =
        api.post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(tenant))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(externalId, channel, content))
            .exchange()
            .expectStatus()
            .isEqualTo(202)
            .expectBody()
            .returnResult();
    final String id = MAPPER.readTree(result.getResponseBody()).get("notificationId").asText();
    assertNotNull(id);
    return id;
  }

  private void awaitStatus(final String tenant, final String id, final String expected)
      throws Exception {
    final Instant deadline = Instant.now().plus(LIMIT);
    String status = null;
    while (Instant.now().isBefore(deadline)) {
      final EntityExchangeResult<byte[]> result =
          api.get()
              .uri("/notifications/" + id)
              .header("Authorization", TestTokens.bearer(tenant))
              .exchange()
              .expectBody()
              .returnResult();
      final var node = MAPPER.readTree(result.getResponseBody());
      status = node.has("status") ? node.get("status").asText() : null;
      if (expected.equals(status)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("status never reached " + expected + ", last " + status);
  }

  private void send(
      final String tenant,
      final String prefix,
      final String channel,
      final AttemptResult result,
      final String finalStatus,
      final int count)
      throws Exception {
    final String provider = channel.equals("SMS") ? "probe-sms" : "probe-mail";
    RESULTS.get(provider).set(result);
    final List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(accept(tenant, prefix + "-" + i, channel, "Body " + i));
    }
    for (final String id : ids) {
      awaitStatus(tenant, id, finalStatus);
    }
  }

  @Test
  void countersMatchTheNotificationsSentPerChannelProviderAndResultExactly() throws Exception {
    final String run = "m1-" + System.nanoTime();
    final List<Series> before = parse(scrapeText());

    send("tenant-a", run + "-em-ok", "EMAIL", AttemptResult.ACCEPTED, "DELIVERED", 3);
    send("tenant-a", run + "-em-rec", "EMAIL", AttemptResult.RECOVERABLE_FAILURE, "RECOVERABLE", 2);
    send("tenant-a", run + "-em-bad", "EMAIL", AttemptResult.PERMANENT_FAILURE, "FAILED", 1);
    send("tenant-a", run + "-sm-ok", "SMS", AttemptResult.ACCEPTED, "DELIVERED", 2);
    send("tenant-a", run + "-sm-bad", "SMS", AttemptResult.PERMANENT_FAILURE, "FAILED", 1);

    api.post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-a"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(run + "-em-ok-0", "EMAIL", "Body 0"))
        .exchange()
        .expectStatus()
        .is2xxSuccessful();

    final List<Series> after = parse(scrapeText());
    assertEquals(6, delta(before, after, "notification_accepted_total", "channel", "EMAIL"));
    assertEquals(3, delta(before, after, "notification_accepted_total", "channel", "SMS"));
    assertEquals(
        6,
        delta(
            before,
            after,
            "notification_attempts_total",
            "channel",
            "EMAIL",
            "provider",
            "probe-mail"));
    assertEquals(
        3,
        delta(
            before,
            after,
            "notification_attempts_total",
            "channel",
            "SMS",
            "provider",
            "probe-sms"));
    assertEquals(3, dispatched(before, after, "EMAIL", "probe-mail", "delivered"));
    assertEquals(2, dispatched(before, after, "EMAIL", "probe-mail", "recoverable"));
    assertEquals(1, dispatched(before, after, "EMAIL", "probe-mail", "failed"));
    assertEquals(2, dispatched(before, after, "SMS", "probe-sms", "delivered"));
    assertEquals(1, dispatched(before, after, "SMS", "probe-sms", "failed"));
    assertEquals(0, dispatched(before, after, "SMS", "probe-sms", "recoverable"));
    assertEquals(
        3,
        delta(
            before,
            after,
            "notification_provider_duration_seconds_count",
            "provider",
            "probe-mail",
            "result",
            "delivered"));
    assertEquals(
        1,
        delta(
            before,
            after,
            "notification_provider_duration_seconds_count",
            "provider",
            "probe-sms",
            "result",
            "failed"));
    assertTrue(
        after.stream()
            .anyMatch(
                entry ->
                    entry.name().equals("notification_provider_duration_seconds_bucket")
                        && "probe-mail".equals(entry.labels().get("provider"))),
        "the provider duration must expose histogram buckets for p95 and p99");
  }

  private static double dispatched(
      final List<Series> before,
      final List<Series> after,
      final String channel,
      final String provider,
      final String result) {
    return delta(
        before,
        after,
        "notification_dispatched_total",
        "channel",
        channel,
        "provider",
        provider,
        "result",
        result);
  }

  private static double delta(
      final List<Series> before,
      final List<Series> after,
      final String name,
      final String... labelPairs) {
    return sum(after, name, labelPairs) - sum(before, name, labelPairs);
  }

  @Test
  void twoTenantsAggregateIntoTheSameSeriesWithoutAnyTenantLabel() throws Exception {
    final String run = "m2-" + System.nanoTime();
    final List<Series> before = parse(scrapeText());

    send("tenant-a", run + "-a", "EMAIL", AttemptResult.ACCEPTED, "DELIVERED", 2);
    send("tenant-b", run + "-b", "EMAIL", AttemptResult.ACCEPTED, "DELIVERED", 3);

    final String text = scrapeText();
    final List<Series> after = parse(text);
    assertEquals(5, dispatched(before, after, "EMAIL", "probe-mail", "delivered"));
    assertTrue(
        after.stream()
            .filter(entry -> entry.name().startsWith("notification_"))
            .noneMatch(entry -> entry.labels().containsKey("tenantId")));
    assertFalse(text.contains("tenantId"));
    assertFalse(text.contains("tenant-a"));
    assertFalse(text.contains("tenant-b"));
  }

  @Test
  void sentinelDataNeverAppearsInTheExposedMetrics() throws Exception {
    final String run = "m3-" + System.nanoTime();
    final String id = accept("tenant-a", run, "EMAIL", SENTINEL_CONTENT);
    awaitStatus("tenant-a", id, "DELIVERED");

    final String token = TestTokens.bearer("tenant-a").replace("Bearer ", "");
    final String text = scrapeText();

    final List<String> sentinels =
        List.of(SENTINEL_RECIPIENT, SENTINEL_CONTENT, token, TestTokens.SECRET, id, run);
    for (final String sentinel : sentinels) {
      assertFalse(text.contains(sentinel), "metrics leaked " + sentinel);
    }
    assertTrue(text.contains("channel=\"EMAIL\""), "positive control: allowed values do appear");
    for (final String sentinel : sentinels) {
      assertTrue((text + sentinel).contains(sentinel), "the check detects " + sentinel);
    }
  }

  @Test
  void theMetricsEndpointRespondsWithinTwoSecondsAfterLoad() throws Exception {
    final String run = "m4-" + System.nanoTime();
    send("tenant-a", run, "EMAIL", AttemptResult.ACCEPTED, "DELIVERED", 25);

    final Instant start = Instant.now();
    final String text = scrapeText();
    final Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(text.contains("notification_accepted_total"));
    assertTrue(
        elapsed.compareTo(Duration.ofSeconds(2)) <= 0, "the scrape took " + elapsed.toMillis());
  }

  @Test
  void technicalMetricsCoverTheApiTheRabbitConsumerAndTheJvm() throws Exception {
    final String run = "m5-" + System.nanoTime();
    send("tenant-a", run, "EMAIL", AttemptResult.ACCEPTED, "DELIVERED", 1);

    final List<Series> series = parse(scrapeText());
    assertTrue(
        series.stream()
            .anyMatch(
                entry ->
                    entry.name().equals("http_server_requests_seconds_bucket")
                        && "/notifications".equals(entry.labels().get("uri"))),
        "http.server.requests must expose a histogram for percentiles");
    assertTrue(sum(series, "http_server_requests_seconds_count", "uri", "/notifications") >= 1);
    assertTrue(
        series.stream().anyMatch(entry -> entry.name().startsWith("spring_rabbitmq_listener")),
        "the Rabbit consumer must be measured");
    assertTrue(series.stream().anyMatch(entry -> entry.name().equals("jvm_memory_used_bytes")));
    assertTrue(series.stream().anyMatch(entry -> entry.name().equals("jvm_threads_live_threads")));
  }
}
