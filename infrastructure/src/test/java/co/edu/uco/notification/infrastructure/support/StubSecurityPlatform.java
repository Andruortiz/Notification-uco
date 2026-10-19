package co.edu.uco.notification.infrastructure.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class StubSecurityPlatform implements AutoCloseable {

  public static final String COOKIE_NAME = "SECURITY_BASELINE_SESSION";

  private static final String SESSION_PATH = "/api/v1/session";
  private static final String DECISION_PATH = "/internal/v1/embedded-access-decisions";

  private final HttpServer server;
  private final Map<String, String> tenantBySession = new ConcurrentHashMap<>();
  private final List<String> sessionCookiesReceived = new CopyOnWriteArrayList<>();
  private final AtomicInteger sessionCalls = new AtomicInteger();
  private final AtomicInteger decisionCalls = new AtomicInteger();
  private volatile int forcedSessionStatus;
  private volatile Duration sessionDelay = Duration.ZERO;
  private volatile String forcedSessionBody;

  public StubSecurityPlatform() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(SESSION_PATH, this::session);
    server.createContext(DECISION_PATH, this::decision);
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public StubSecurityPlatform withSession(final String cookieValue, final String tenantId) {
    tenantBySession.put(cookieValue, tenantId);
    return this;
  }

  public void revoke(final String cookieValue) {
    tenantBySession.remove(cookieValue);
  }

  public void failSessionWith(final int status) {
    forcedSessionStatus = status;
  }

  public void delaySessionBy(final Duration delay) {
    sessionDelay = delay;
  }

  public void answerSessionWith(final String rawBody) {
    forcedSessionBody = rawBody;
  }

  public void reset() {
    forcedSessionStatus = 0;
    sessionDelay = Duration.ZERO;
    forcedSessionBody = null;
    sessionCalls.set(0);
    decisionCalls.set(0);
    sessionCookiesReceived.clear();
  }

  public int sessionCalls() {
    return sessionCalls.get();
  }

  public int decisionCalls() {
    return decisionCalls.get();
  }

  public List<String> sessionCookiesReceived() {
    return List.copyOf(sessionCookiesReceived);
  }

  public static String sessionBody(final String subject, final String tenantId) {
    return "{\"code\":\"SESSION_ACTIVE\",\"message\":\"Authenticated session\",\"data\":{"
        + "\"subject\":\""
        + subject
        + "\",\"name\":\"\",\"email\":\""
        + subject
        + "@example.com\",\"tenantId\":\""
        + tenantId
        + "\"},\"timestamp\":\"2026-10-19T10:00:00Z\",\"requestId\":\"r-1\","
        + "\"correlationId\":\"c-1\"}";
  }

  private void session(final HttpExchange exchange) throws IOException {
    sessionCalls.incrementAndGet();
    final String cookie = sessionCookie(exchange.getRequestHeaders().getFirst("Cookie"));
    sessionCookiesReceived.add(String.valueOf(cookie));
    pause(sessionDelay);
    if (forcedSessionStatus != 0) {
      respond(exchange, forcedSessionStatus, "{\"code\":\"FORCED\"}");
      return;
    }
    if (forcedSessionBody != null) {
      respond(exchange, 200, forcedSessionBody);
      return;
    }
    final String tenant = cookie == null ? null : tenantBySession.get(cookie);
    if (tenant == null) {
      respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
      return;
    }
    respond(exchange, 200, sessionBody("user-" + cookie, tenant));
  }

  private void decision(final HttpExchange exchange) throws IOException {
    decisionCalls.incrementAndGet();
    exchange.getRequestBody().readAllBytes();
    final boolean bearer = exchange.getRequestHeaders().getFirst("Authorization") != null;
    final String cookie = sessionCookie(exchange.getRequestHeaders().getFirst("Cookie"));
    if (bearer || (cookie != null && tenantBySession.containsKey(cookie))) {
      exchange.getResponseHeaders().add("X-Decision-Id", "decision-1");
      respond(exchange, 200, "{}");
      return;
    }
    respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
  }

  private static String sessionCookie(final String header) {
    if (header == null) {
      return null;
    }
    for (final String part : header.split(";")) {
      final String trimmed = part.trim();
      if (trimmed.startsWith(COOKIE_NAME + "=")) {
        return trimmed.substring(COOKIE_NAME.length() + 1);
      }
    }
    return null;
  }

  private static void pause(final Duration delay) {
    if (delay.isZero()) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
