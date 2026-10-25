package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.support.DashboardQueries;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.channels.EMAIL.providers[0]=simulated",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@org.testcontainers.junit.jupiter.Testcontainers
class LoadTestVerdictE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final int WARMUP_REQUESTS = 20;
  private static final int MEASURED_REQUESTS = 100;
  private static final long SPACING_MILLIS = 80;
  private static final Duration DRAIN_LIMIT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

  @Autowired private MeterRegistry registry;

  private double delivered() {
    return registry
        .find("notification.dispatched")
        .tag("result", "delivered")
        .tag("provider", "simulated")
        .counters()
        .stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  private int post(final WebTestClient client, final String id) {
    return client
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("loadtest-tenant"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "externalId",
                id,
                "channelType",
                "EMAIL",
                "recipientId",
                "loadtest-recipient",
                "recipientAddress",
                "loadtest@example.invalid",
                "subject",
                "Prueba de carga",
                "body",
                "Prueba de carga",
                "priority",
                "NORMAL"))
        .exchange()
        .returnResult(Void.class)
        .getStatus()
        .value();
  }

  private Map<String, Object> measure() throws Exception {
    final WebTestClient client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(10))
            .build();
    final String run = UUID.randomUUID().toString();
    final double initial = delivered();
    for (int i = 0; i < WARMUP_REQUESTS; i++) {
      post(client, "warmup-" + run + "-" + i);
    }
    awaitDelivered(initial, WARMUP_REQUESTS);

    final double before = delivered();
    final List<Double> seconds = new ArrayList<>();
    int accepted = 0;
    final long start = System.nanoTime();
    for (int i = 0; i < MEASURED_REQUESTS; i++) {
      final long slot = start + TimeUnit.MILLISECONDS.toNanos(i * SPACING_MILLIS);
      final long wait = slot - System.nanoTime();
      if (wait > 0) {
        TimeUnit.NANOSECONDS.sleep(wait);
      }
      final long begin = System.nanoTime();
      if (post(client, "measured-" + run + "-" + i) == 202) {
        accepted++;
      }
      seconds.add((System.nanoTime() - begin) / 1e9);
    }
    final double windowMinutes = (System.nanoTime() - start) / 1e9 / 60;
    awaitDelivered(before, MEASURED_REQUESTS);

    Collections.sort(seconds);
    return Map.of(
        "p95Seconds",
        seconds.get((int) Math.ceil(0.95 * seconds.size()) - 1),
        "ratio202",
        accepted / (double) MEASURED_REQUESTS,
        "despachoPorMinuto",
        (delivered() - before) / windowMinutes,
        "contadorReiniciado",
        false);
  }

  private void awaitDelivered(final double before, final int expected) throws Exception {
    final Instant deadline = Instant.now().plus(DRAIN_LIMIT);
    while (Instant.now().isBefore(deadline) && delivered() - before < expected) {
      Thread.sleep(50);
    }
  }

  private record Verdict(int exitCode, JsonNode body) {}

  private static Verdict evaluate(
      final Map<String, Object> measurements, final Map<String, Object> thresholds)
      throws IOException, InterruptedException {
    final Process process =
        new ProcessBuilder(
                "node",
                DashboardQueries.repositoryFile("loadtest/evaluar.js").toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    process
        .getOutputStream()
        .write(
            MAPPER
                .writeValueAsString(Map.of("mediciones", measurements, "umbrales", thresholds))
                .getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Verdict(process.waitFor(), MAPPER.readTree(output));
  }

  @Test
  void theMeasuredRunMeetsRnf02AndRnf03AndAnUnreachableThresholdFails() throws Exception {
    final Map<String, Object> measurements = measure();

    assertTrue((double) measurements.get("ratio202") >= 0.99, "202 ratio " + measurements);
    final Verdict real = evaluate(measurements, Map.of());
    assertEquals(0, real.exitCode(), real.body().toString() + " " + measurements);
    assertTrue(real.body().get("ok").asBoolean());

    final Verdict unreachable = evaluate(measurements, Map.of("despachoPorMinuto", 1_000_000));
    assertEquals(1, unreachable.exitCode());
    assertTrue(unreachable.body().get("fallos").get(0).asText().contains("RNF-03"));

    final Map<String, Object> reset = new java.util.HashMap<>(measurements);
    reset.put("contadorReiniciado", true);
    final Verdict invalid = evaluate(reset, Map.of());
    assertEquals(1, invalid.exitCode());
    assertTrue(invalid.body().get("fallos").get(0).asText().contains("invalida"));
  }
}
