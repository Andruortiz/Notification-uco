package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.support.NotificationFlow;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TracingResilienceE2ETest {

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final Duration LIMIT = Duration.ofSeconds(60);
  private static final String TENANT = "tenant-a";
  private static final int WARM_UP = 15;
  private static final int MEASURED = 60;
  private static final String UNREACHABLE = "http://127.0.0.1:1/v1/traces";

  private record Run(Duration p95, int accepted, int delivered) {}

  private static ConfigurableApplicationContext start(final String endpoint) {
    final List<String> properties =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.main.banner-mode=off",
                "--MONGO_USERNAME=test",
                "--MONGO_PASSWORD=test",
                "--spring.data.mongodb.uri=" + MONGO.getReplicaSetUrl("notification"),
                "--RABBITMQ_USERNAME=guest",
                "--RABBITMQ_PASSWORD=guest",
                "--spring.rabbitmq.host=" + RABBIT.getHost(),
                "--spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                "--notification.catalog.refresh-interval-ms=500",
                "--notification.scheduler.requeue-interval-ms=600000",
                "--management.tracing.sampling.probability=1.0"));
    if (endpoint != null) {
      properties.add("--management.otlp.tracing.endpoint=" + endpoint);
    }
    return new SpringApplicationBuilder(NotificationServiceApplication.class)
        .profiles("local")
        .run(properties.toArray(String[]::new));
  }

  private static void awaitRoute(final ConfigurableApplicationContext context)
      throws InterruptedException {
    final ChannelCatalogPort catalog = context.getBean(ChannelCatalogPort.class);
    final Instant deadline = Instant.now().plus(LIMIT);
    while (Instant.now().isBefore(deadline)) {
      if (catalog
          .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("channel EMAIL never became active");
  }

  private static Run measure(final ConfigurableApplicationContext context) throws Exception {
    awaitRoute(context);
    final int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    final NotificationFlow flow = new NotificationFlow(NotificationFlow.client(port, LIMIT));
    final List<String> ids = new ArrayList<>();
    final List<Long> nanos = new ArrayList<>();
    for (int i = 0; i < WARM_UP + MEASURED; i++) {
      final Instant start = Instant.now();
      final String id = flow.accept(TENANT, "ext-" + UUID.randomUUID(), null, null);
      final long elapsed = Duration.between(start, Instant.now()).toNanos();
      ids.add(id);
      if (i >= WARM_UP) {
        nanos.add(elapsed);
      }
    }
    int delivered = 0;
    for (final String id : ids) {
      flow.awaitStatus(TENANT, id, "DELIVERED", LIMIT);
      delivered++;
    }
    Collections.sort(nanos);
    final long p95 = nanos.get((int) Math.ceil(0.95 * nanos.size()) - 1);
    return new Run(Duration.ofNanos(p95), ids.size(), delivered);
  }

  @Test
  void anUnreachableOtlpEndpointNeitherLosesNotificationsNorSlowsAcceptance() throws Exception {
    final Run baseline;
    try (ConfigurableApplicationContext context = start(null)) {
      assertTrue(context.getBeansOfType(SpanExporter.class).isEmpty());
      assertTrue(context.getBeansOfType(OtlpHttpSpanExporter.class).isEmpty());
      baseline = measure(context);
    }

    final Run degraded;
    try (ConfigurableApplicationContext context = start(UNREACHABLE)) {
      assertEquals(1, context.getBeansOfType(OtlpHttpSpanExporter.class).size());
      degraded = measure(context);
    }

    assertEquals(baseline.accepted(), baseline.delivered());
    assertEquals(degraded.accepted(), degraded.delivered());
    final Duration allowed =
        baseline.p95().multipliedBy(110).dividedBy(100).plus(Duration.ofMillis(20));
    assertTrue(
        degraded.p95().compareTo(allowed) <= 0,
        "p95 with an unreachable exporter " + degraded.p95() + " exceeds " + allowed);
  }
}
