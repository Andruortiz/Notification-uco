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
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.support.DashboardQueries;
import co.edu.uco.notification.infrastructure.support.DashboardQueries.Query;
import co.edu.uco.notification.infrastructure.support.PrometheusText;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import co.edu.uco.notification.utils.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.yaml.snakeyaml.Yaml;
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
@Import(ObservabilityDashboardE2ETest.ProbeSenderConfig.class)
@org.testcontainers.junit.jupiter.Testcontainers
class ObservabilityDashboardE2ETest {

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

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration LIMIT = Duration.ofSeconds(30);
  private static final Duration SCRAPE_LIMIT = Duration.ofSeconds(2);
  private static final String SENTINEL_RECIPIENT = "dashboard-recipient@secret.invalid";
  private static final String DASHBOARD =
      "observability/grafana/dashboards/notification-service.json";
  private static final String RULES = "observability/alerts.yml";
  private static final String PROMETHEUS_CONFIG = "observability/prometheus.yml";
  private static final String SCRAPE_TARGET = "host.docker.internal:8061";

  @LocalServerPort private int apiPort;

  @LocalManagementPort private int managementPort;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private NotificationMetricsPort metricsPort;

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

  private String accept(final String tenant, final String externalId, final String channel)
      throws Exception {
    final EntityExchangeResult<byte[]> result =
        api.post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(tenant))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of(
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
                    "Body",
                    "priority",
                    "NORMAL"))
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
      final JsonNode node = MAPPER.readTree(result.getResponseBody());
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
    RESULTS.get(channel.equals("SMS") ? "probe-sms" : "probe-mail").set(result);
    final List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(accept(tenant, prefix + "-" + i, channel));
    }
    for (final String id : ids) {
      awaitStatus(tenant, id, finalStatus);
    }
  }

  private String generateTrafficAndScrape() throws Exception {
    final String run = "dash-" + System.nanoTime();
    send("tenant-a", run + "-ok", "EMAIL", AttemptResult.ACCEPTED, "DELIVERED", 3);
    send("tenant-b", run + "-rec", "EMAIL", AttemptResult.RECOVERABLE_FAILURE, "RECOVERABLE", 2);
    send("tenant-a", run + "-bad", "SMS", AttemptResult.PERMANENT_FAILURE, "FAILED", 2);
    send("tenant-b", run + "-sms", "SMS", AttemptResult.ACCEPTED, "DELIVERED", 1);
    api.get()
        .uri("/notifications/unknown-" + run)
        .header("Authorization", TestTokens.bearer("tenant-a"))
        .exchange()
        .expectStatus()
        .isNotFound();
    metricsPort.errorRecorded(
        ErrorCode.PROVIDER_ERROR, ChannelType.of("EMAIL"), ProviderId.of("probe-mail"));
    metricsPort.errorRecorded(ErrorCode.INFRASTRUCTURE_ERROR);

    final Instant start = Instant.now();
    final String text = scrapeText();
    final Duration elapsed = Duration.between(start, Instant.now());
    assertTrue(
        elapsed.compareTo(SCRAPE_LIMIT) <= 0, "the scrape took " + elapsed.toMillis() + " ms");
    return text;
  }

  private static List<Query> allQueries() {
    final List<Query> queries =
        new ArrayList<>(
            DashboardQueries.fromDashboard(
                DashboardQueries.read(DashboardQueries.repositoryFile(DASHBOARD))));
    queries.addAll(
        DashboardQueries.fromRules(DashboardQueries.read(DashboardQueries.repositoryFile(RULES))));
    return queries;
  }

  @Test
  void everyMetricAndLabelUsedByTheDashboardAndTheRulesIsExposedByTheService() throws Exception {
    final String text = generateTrafficAndScrape();
    final List<Query> queries = allQueries();

    assertTrue(queries.size() >= 20, "the dashboard must carry its panels");
    assertEquals(
        List.of(), DashboardQueries.problems(queries, PrometheusText.labelsByMetric(text)));
  }

  @Test
  void aDashboardReferencingAMissingMetricMakesTheVerifierFail() throws Exception {
    final String text = generateTrafficAndScrape();
    final String tampered =
        DashboardQueries.read(DashboardQueries.repositoryFile(DASHBOARD))
            .replace("notification_accepted_total", "notification_accepted_missing_total");

    final List<String> problems =
        DashboardQueries.problems(
            DashboardQueries.fromDashboard(tampered), PrometheusText.labelsByMetric(text));

    assertFalse(problems.isEmpty());
    assertTrue(problems.stream().anyMatch(problem -> problem.contains("accepted_missing")));
  }

  @Test
  void theDashboardTheRulesAndTheScrapeCarryNoTenantNorRecipientData() throws Exception {
    final String text = generateTrafficAndScrape();

    for (final String file : List.of(DASHBOARD, RULES, PROMETHEUS_CONFIG)) {
      assertEquals(
          List.of(),
          DashboardQueries.forbiddenReferences(
              DashboardQueries.read(DashboardQueries.repositoryFile(file))),
          file);
    }
    assertFalse(text.contains("tenant-a"));
    assertFalse(text.contains("tenant-b"));
    assertFalse(text.contains(SENTINEL_RECIPIENT));
    assertTrue(text.contains("channel=\"EMAIL\""), "positive control: allowed values do appear");
    assertTrue(
        text.contains("notification_accepted_total"), "positive control: the series are emitted");
  }

  @Test
  void aRealPrometheusScrapesTheServiceLoadsTheRulesAndEvaluatesEveryQuery() throws Exception {
    generateTrafficAndScrape();
    Testcontainers.exposeHostPorts(managementPort);
    final Path config = renderedPrometheusConfig();

    try (GenericContainer<?> prometheus =
        new GenericContainer<>(prometheusImage())
            .withExposedPorts(9090)
            .withFileSystemBind(
                config.toAbsolutePath().toString(),
                "/etc/prometheus/prometheus.yml",
                BindMode.READ_ONLY)
            .withFileSystemBind(
                DashboardQueries.repositoryFile(RULES).toAbsolutePath().toString(),
                "/etc/prometheus/alerts.yml",
                BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/-/ready").forPort(9090))) {
      prometheus.start();
      final String base = "http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090);

      awaitTargetUp(base);
      for (final Query query : allQueries()) {
        final JsonNode result =
            get(
                base
                    + "/api/v1/query?query="
                    + encode(DashboardQueries.render(query.expression())));
        assertEquals("success", result.get("status").asText(), query.source());
      }
      final JsonNode up =
          get(base + "/api/v1/query?query=" + encode("up{job=\"notification-service\"}"));
      assertEquals("1", up.at("/data/result/0/value/1").asText());
      final Set<String> alerts = new java.util.HashSet<>();
      get(base + "/api/v1/rules")
          .at("/data/groups")
          .forEach(
              group -> group.get("rules").forEach(rule -> alerts.add(rule.get("name").asText())));
      assertEquals(Set.of("NotificationDispatchFailedHigh", "NotificationServiceDown"), alerts);
    }
  }

  private Path renderedPrometheusConfig() throws IOException {
    final String rendered =
        DashboardQueries.read(DashboardQueries.repositoryFile(PROMETHEUS_CONFIG))
            .replace(SCRAPE_TARGET, "host.testcontainers.internal:" + managementPort);
    assertTrue(rendered.contains("host.testcontainers.internal"), "the scrape target must exist");
    final Path file = Files.createTempFile("prometheus", ".yml");
    Files.writeString(file, rendered, StandardCharsets.UTF_8);
    file.toFile().setReadable(true, false);
    file.toFile().deleteOnExit();
    return file;
  }

  @SuppressWarnings("unchecked")
  private static String prometheusImage() {
    final Map<String, Object> compose =
        new Yaml()
            .load(
                DashboardQueries.read(
                    DashboardQueries.repositoryFile("docker-compose.observability.yml")));
    return (String)
        ((Map<String, Map<String, Object>>) compose.get("services")).get("prometheus").get("image");
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static JsonNode get(final String url) throws Exception {
    final HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    return MAPPER.readTree(response.body());
  }

  private static void awaitTargetUp(final String base) throws Exception {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
    String health = "none";
    while (Instant.now().isBefore(deadline)) {
      final JsonNode targets = get(base + "/api/v1/targets").at("/data/activeTargets");
      if (targets.size() > 0) {
        health = targets.get(0).get("health").asText();
        if ("up".equals(health)) {
          return;
        }
      }
      Thread.sleep(500);
    }
    throw new AssertionError("the scrape target never became up, last " + health);
  }
}
