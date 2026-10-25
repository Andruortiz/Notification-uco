package co.edu.uco.notification.infrastructure.adapter.out.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.exception.ProviderMetricsUnavailableException;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort.Snapshot;
import co.edu.uco.notification.infrastructure.config.ProviderHealthProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.core.JacksonException;

class PrometheusProviderMetricsAdapterTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  private record Reply(int status, String body) {}

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private PrometheusProviderMetricsAdapter adapterWith(
      final BiFunction<String, String, Reply> responder) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/api/v1/",
        exchange -> {
          final Reply reply =
              responder.apply(
                  exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery());
          final byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(reply.status(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return new PrometheusProviderMetricsAdapter(
        new ProviderHealthProperties(
            "http://localhost:" + server.getAddress().getPort(),
            null,
            null,
            null,
            Duration.ofMillis(500),
            null,
            null,
            null,
            null));
  }

  private static Snapshot run(final PrometheusProviderMetricsAdapter adapter) {
    return adapter.query(NOW, Duration.ofMinutes(5), Duration.ofMinutes(15)).block();
  }

  private static StepVerifier.FirstStep<Snapshot> verify(
      final PrometheusProviderMetricsAdapter adapter) {
    return StepVerifier.create(adapter.query(NOW, Duration.ofMinutes(5), Duration.ofMinutes(15)));
  }

  private static String vector(final String... samples) {
    return "{\"status\":\"success\",\"data\":{\"result\":[" + String.join(",", samples) + "]}}";
  }

  private static String counts(final String result, final String value) {
    return "{\"metric\":{\"provider\":\"brevo\",\"channel\":\"EMAIL\",\"result\":\""
        + result
        + "\"},\"value\":[1,\""
        + value
        + "\"]}";
  }

  @Test
  void parsesCountsLatencyAndErrorMarks() throws IOException {
    final PrometheusProviderMetricsAdapter adapter =
        adapterWith(
            (path, query) -> {
              if (path.endsWith("query_range")) {
                return new Reply(
                    200,
                    vector(
                        "{\"metric\":{\"provider\":\"brevo\",\"channel\":\"EMAIL\","
                            + "\"result\":\"failed\"},\"values\":[[1000,\"0\"],[1060,\"2\"]]}"));
              }
              if (query.contains("histogram_quantile")) {
                return new Reply(
                    200, vector("{\"metric\":{\"provider\":\"brevo\"},\"value\":[1,\"0.5\"]}"));
              }
              return new Reply(200, vector(counts("delivered", "9"), counts("failed", "1")));
            });

    final Snapshot snapshot = run(adapter);

    assertEquals(1, snapshot.counts().size());
    assertEquals(9.0, snapshot.counts().get(0).delivered());
    assertEquals(1.0, snapshot.counts().get(0).failed());
    assertEquals(0.5, snapshot.latencyP95SecondsByProvider().get("brevo"));
    assertEquals(1, snapshot.errors().size());
    assertEquals(Instant.ofEpochSecond(1060), snapshot.errors().get(0).at());
  }

  @Test
  void ignoresNaNLatencyAndEmptyResults() throws IOException {
    final PrometheusProviderMetricsAdapter adapter =
        adapterWith(
            (path, query) ->
                query.contains("histogram_quantile")
                    ? new Reply(
                        200, vector("{\"metric\":{\"provider\":\"brevo\"},\"value\":[1,\"NaN\"]}"))
                    : new Reply(200, vector()));

    final Snapshot snapshot = run(adapter);

    assertTrue(snapshot.counts().isEmpty());
    assertTrue(snapshot.latencyP95SecondsByProvider().isEmpty());
    assertTrue(snapshot.errors().isEmpty());
  }

  @Test
  void serverErrorBecomesUnavailable() throws IOException {
    verify(adapterWith((path, query) -> new Reply(500, "boom")))
        .expectError(ProviderMetricsUnavailableException.class)
        .verify();
  }

  @Test
  void errorStatusInTheBodyBecomesUnavailable() throws IOException {
    verify(adapterWith((path, query) -> new Reply(200, "{\"status\":\"error\"}")))
        .expectError(ProviderMetricsUnavailableException.class)
        .verify();
  }

  @Test
  void slowSourceBecomesUnavailable() throws IOException {
    verify(
            adapterWith(
                (path, query) -> {
                  try {
                    Thread.sleep(1500);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return new Reply(200, vector());
                }))
        .expectError(ProviderMetricsUnavailableException.class)
        .verify();
  }

  @Test
  void connectionRefusedBecomesUnavailable() throws IOException {
    final PrometheusProviderMetricsAdapter adapter =
        adapterWith((path, query) -> new Reply(200, vector()));
    server.stop(0);

    verify(adapter).expectError(ProviderMetricsUnavailableException.class).verify();
  }

  @Test
  void parseErrorsPropagateInsteadOfBeingMasked() throws IOException {
    verify(adapterWith((path, query) -> new Reply(200, "not json")))
        .expectError(JacksonException.class)
        .verify();
  }
}
