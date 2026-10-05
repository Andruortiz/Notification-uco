package co.edu.uco.notification.infrastructure.adapter.out.parameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.exception.ParametersUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class HttpParametersSourceTest {

  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{}");
  private final AtomicInteger delayMs = new AtomicInteger(0);
  private final List<String> paths = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          try {
            Thread.sleep(delayMs.get());
          } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          final byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private HttpParametersSource source(final Duration timeout) {
    return new HttpParametersSource(
        WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
        timeout);
  }

  @Test
  void aValidStateBecomesAConfigurationChange() {
    body.set(
        "{\"version\":12,\"values\":{\"dispatch.max-attempts\":5,\"requeue.interval-ms\":20000}}");

    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .assertNext(
            change -> {
              assertEquals(12, change.version());
              assertEquals(5, change.values().get("dispatch.max-attempts"));
              assertEquals(20_000, change.values().get("requeue.interval-ms"));
            })
        .verifyComplete();

    assertEquals(List.of("GET /notification-service/configuration"), paths);
  }

  @Test
  void aStatusOtherThan200IsAnUnavailableSourceWhileA200IsNot() {
    status.set(503);
    body.set("{\"version\":12,\"values\":{}}");
    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .expectError(ParametersUnavailableException.class)
        .verify();

    status.set(404);
    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .expectError(ParametersUnavailableException.class)
        .verify();

    status.set(200);
    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .assertNext(change -> assertEquals(12, change.version()))
        .verifyComplete();
  }

  @Test
  void aSlowSourceIsUnavailableWhileAFastOneIsNot() {
    body.set("{\"version\":3,\"values\":{}}");
    delayMs.set(1_500);
    StepVerifier.create(source(Duration.ofMillis(300)).fetchState())
        .expectError(ParametersUnavailableException.class)
        .verify(Duration.ofSeconds(5));

    delayMs.set(0);
    StepVerifier.create(source(Duration.ofMillis(300)).fetchState())
        .assertNext(change -> assertEquals(3, change.version()))
        .verifyComplete();
  }

  @Test
  void anUnreadableOrIncompleteBodyIsAnUnavailableSource() {
    body.set("this is not json");
    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .expectError(ParametersUnavailableException.class)
        .verify();

    body.set("{\"values\":{\"dispatch.max-attempts\":5}}");
    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .expectError(ParametersUnavailableException.class)
        .verify();

    body.set("{\"version\":4}");
    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .expectError(ParametersUnavailableException.class)
        .verify();

    body.set("{\"version\":4,\"values\":{}}");
    StepVerifier.create(source(Duration.ofSeconds(2)).fetchState())
        .assertNext(change -> assertTrue(change.values().isEmpty()))
        .verifyComplete();
  }

  @Test
  void anUnreachableServerIsAnUnavailableSource() {
    final int deadPort = server.getAddress().getPort();
    server.stop(0);
    final HttpParametersSource unreachable =
        new HttpParametersSource(
            WebClient.builder().baseUrl("http://127.0.0.1:" + deadPort).build(),
            Duration.ofSeconds(2));

    StepVerifier.create(unreachable.fetchState())
        .expectError(ParametersUnavailableException.class)
        .verify(Duration.ofSeconds(5));
  }
}
