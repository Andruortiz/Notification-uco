package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.support.InMemorySpans;
import co.edu.uco.notification.infrastructure.support.InMemoryTracingConfig;
import co.edu.uco.notification.infrastructure.support.LogCapture;
import co.edu.uco.notification.infrastructure.support.NotificationFlow;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Import(InMemoryTracingConfig.class)
@Testcontainers
class ObservabilityE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final Duration LIMIT = Duration.ofSeconds(30);
  private static final String TENANT = "tenant-a";

  @LocalServerPort private int apiPort;

  @LocalManagementPort private int managementPort;

  @Autowired private InMemorySpans spans;
  @Autowired private ChannelCatalogPort channelCatalogPort;

  private String scrape(final WebTestClient management) {
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

  private static double sum(final String text, final String name, final String... fragments) {
    double total = 0;
    for (final String line : text.split("\n")) {
      if (!line.startsWith(name + "{")) {
        continue;
      }
      boolean matches = true;
      for (final String fragment : fragments) {
        matches &= line.contains(fragment);
      }
      if (matches) {
        total += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1).trim());
      }
    }
    return total;
  }

  private void awaitRoute() throws InterruptedException {
    final Instant deadline = Instant.now().plus(LIMIT);
    while (Instant.now().isBefore(deadline)) {
      if (channelCatalogPort
          .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("channel EMAIL never became active");
  }

  @Test
  void oneNotificationYieldsMetricsTracesAndLogsLinkedByTheSameIdentifiers() throws Exception {
    awaitRoute();
    final NotificationFlow flow = new NotificationFlow(NotificationFlow.client(apiPort, LIMIT));
    final WebTestClient management = NotificationFlow.client(managementPort, LIMIT);
    final String traceId = UUID.randomUUID().toString().replace("-", "");
    final String correlationId = "obs-" + UUID.randomUUID();
    final String before = scrape(management);

    try (LogCapture logs = LogCapture.start()) {
      final String id =
          flow.accept(
              TENANT,
              "ext-" + UUID.randomUUID(),
              correlationId,
              "00-" + traceId + "-00f067aa0ba902b7-01");
      flow.awaitStatus(TENANT, id, "DELIVERED", LIMIT);
      final List<SpanData> journey = spans.awaitFullJourney(correlationId, LIMIT);
      final String after = scrape(management);

      assertTrue(InMemorySpans.hasFullJourney(journey));
      journey.forEach(span -> assertEquals(traceId, span.getTraceId(), span.getName()));
      final SpanData provider =
          journey.stream()
              .filter(span -> span.getName().equals(InMemorySpans.PROVIDER_CALL))
              .findFirst()
              .orElseThrow();
      final String providerId =
          provider
              .getAttributes()
              .get(io.opentelemetry.api.common.AttributeKey.stringKey("provider"));
      assertEquals(
          "delivered",
          provider
              .getAttributes()
              .get(io.opentelemetry.api.common.AttributeKey.stringKey("result")));

      final List<ILoggingEvent> journeyLogs = logs.withCorrelation(correlationId);
      assertFalse(journeyLogs.isEmpty());
      journeyLogs.forEach(event -> assertEquals(traceId, event.getMDCPropertyMap().get("traceId")));

      assertEquals(
          1,
          sum(after, "notification_accepted_total", "channel=\"EMAIL\"")
              - sum(before, "notification_accepted_total", "channel=\"EMAIL\""));
      assertEquals(
          1,
          sum(
                  after,
                  "notification_dispatched_total",
                  "provider=\"" + providerId + "\"",
                  "result=\"delivered\"")
              - sum(
                  before,
                  "notification_dispatched_total",
                  "provider=\"" + providerId + "\"",
                  "result=\"delivered\""));
      assertFalse(after.contains(correlationId));
      assertFalse(after.contains(traceId));
      assertNotEquals(correlationId, traceId);
    }
  }
}
