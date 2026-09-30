package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenIssuer;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthenticationWebFilterTest {

  private static final String SECRET = "test-only-secret-never-used-in-production-0123456789abcdef";

  private final LocalJwtTokenIssuer issuer = new LocalJwtTokenIssuer(SECRET);
  private final TokenValidationPort tokenValidationPort =
      new LocalJwtTokenValidationAdapter(SECRET);
  private final AuthenticationWebFilter filter = new AuthenticationWebFilter(tokenValidationPort);

  @Test
  void rejectsRequestWithoutAuthorizationHeader() {
    final ServerWebExchange exchange = exchange(HttpMethod.POST, "/notifications");
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void rejectsHeaderWithoutBearerPrefix() {
    final ServerWebExchange exchange =
        exchangeWithHeader(HttpMethod.POST, "/notifications", "Basic abc123");
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void rejectsMalformedToken() {
    final ServerWebExchange exchange =
        exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer not-a-jwt");
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void rejectsExpiredToken() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(-5));
    final ServerWebExchange exchange =
        exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer " + token);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void rejectsTokenMissingTenantIdClaim() {
    final String token =
        Jwts.builder()
            .subject("client-1")
            .claim("role", "CLIENTE")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();
    final ServerWebExchange exchange =
        exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer " + token);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void rejectsTokenWithUnknownRole() {
    final String token =
        Jwts.builder()
            .subject("client-1")
            .claim("tenantId", "tenant-a")
            .claim("role", "SUPERADMIN")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();
    final ServerWebExchange exchange =
        exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer " + token);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void acceptsValidTokenAndContinuesChain() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
    final ServerWebExchange exchange =
        exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer " + token);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertTrue(chainInvoked.get());
  }

  @Test
  void noneOfTheRejectionsLeakTheRawTokenInTheLog() {
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      final String token =
          issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(-5));
      final ServerWebExchange exchange =
          exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer " + token);

      StepVerifier.create(filter.filter(exchange, recordingChain(new AtomicBoolean())))
          .verifyComplete();

      final List<String> lines = lines(logs);
      assertTrue(lines.stream().anyMatch(line -> line.contains("EXPIRED")));
      assertTrue(lines.stream().noneMatch(line -> line.contains(token)));
      assertTrue(lines.stream().noneMatch(line -> line.contains(SECRET)));
    } finally {
      release(logs);
    }
  }

  @Test
  void exemptsOptionsRequestsFromAnyPath() {
    final ServerWebExchange exchange = exchange(HttpMethod.OPTIONS, "/notifications");
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertTrue(chainInvoked.get());
  }

  @Test
  void exemptsActuatorHealth() {
    final ServerWebExchange exchange = exchange(HttpMethod.GET, "/actuator/health");
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertTrue(chainInvoked.get());
  }

  @Test
  void exemptsOpenApiDocumentation() {
    final ServerWebExchange exchangeApiDocs = exchange(HttpMethod.GET, "/v3/api-docs");
    final AtomicBoolean chainInvokedApiDocs = new AtomicBoolean(false);
    StepVerifier.create(filter.filter(exchangeApiDocs, recordingChain(chainInvokedApiDocs)))
        .verifyComplete();
    assertTrue(chainInvokedApiDocs.get());

    final ServerWebExchange exchangeYaml =
        exchange(HttpMethod.GET, "/openapi/api-notificaciones.yaml");
    final AtomicBoolean chainInvokedYaml = new AtomicBoolean(false);
    StepVerifier.create(filter.filter(exchangeYaml, recordingChain(chainInvokedYaml)))
        .verifyComplete();
    assertTrue(chainInvokedYaml.get());
  }

  @Test
  void acceptsAccessTokenQueryParamOnlyForSubscribeEndpoint() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications:subscribe?access_token=" + token).build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertTrue(chainInvoked.get());
  }

  @Test
  void headerTakesPriorityOverAccessTokenQueryParam() {
    final String validToken =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications:subscribe?access_token=not-a-jwt")
                .header("Authorization", "Bearer " + validToken)
                .build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertTrue(chainInvoked.get());
  }

  private static ServerWebExchange exchange(final HttpMethod method, final String path) {
    return MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
  }

  private static ServerWebExchange exchangeWithHeader(
      final HttpMethod method, final String path, final String authorizationHeader) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.method(method, path)
            .header("Authorization", authorizationHeader)
            .build());
  }

  private static WebFilterChain recordingChain(final AtomicBoolean invoked) {
    return exchange -> {
      invoked.set(true);
      return Mono.empty();
    };
  }

  private ListAppender<ILoggingEvent> captureLogs() {
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(AuthenticationWebFilter.class)).addAppender(appender);
    return appender;
  }

  private static void release(final ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(AuthenticationWebFilter.class)).detachAppender(appender);
  }

  private static List<String> lines(final ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }
}
