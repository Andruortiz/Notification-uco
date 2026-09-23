package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class FakeBrevoServer {

  public record RecordedRequest(String path, Map<String, List<String>> headers, String body) {

    public String header(final String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .map(entry -> entry.getValue().get(0))
          .findFirst()
          .orElse(null);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger nextStatusCode = new AtomicInteger(201);
  private final AtomicReference<String> nextResponseBody = new AtomicReference<>("{}");
  private final AtomicLong nextDelayMillis = new AtomicLong(0);

  private FakeBrevoServer(final HttpServer server, final ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  public static FakeBrevoServer start() {
    try {
      final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      final ExecutorService executor = Executors.newCachedThreadPool();
      final FakeBrevoServer fake = new FakeBrevoServer(server, executor);
      server.setExecutor(executor);
      server.createContext("/", fake::handle);
      server.start();
      return fake;
    } catch (final IOException e) {
      throw new IllegalStateException("Could not start FakeBrevoServer", e);
    }
  }

  private void handle(final HttpExchange exchange) throws IOException {
    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(
        new RecordedRequest(
            exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()), body));
    final long delay = nextDelayMillis.get();
    if (delay > 0) {
      try {
        Thread.sleep(delay);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    final byte[] response = nextResponseBody.get().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(nextStatusCode.get(), response.length);
    try (var out = exchange.getResponseBody()) {
      out.write(response);
    }
  }

  public void nextResponse(final int statusCode, final String body) {
    nextStatusCode.set(statusCode);
    nextResponseBody.set(body);
  }

  public void nextDelay(final long millis) {
    nextDelayMillis.set(millis);
  }

  public void reset() {
    requests.clear();
    nextResponse(201, "{}");
    nextDelay(0);
  }

  public List<RecordedRequest> requests() {
    return List.copyOf(requests);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public String baseUrl() {
    return "http://localhost:" + port();
  }

  public void stop() {
    server.stop(0);
    executor.shutdownNow();
  }
}
