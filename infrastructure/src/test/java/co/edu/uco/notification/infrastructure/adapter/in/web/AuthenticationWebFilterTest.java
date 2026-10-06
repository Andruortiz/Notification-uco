package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.SubscriptionTicketFingerprint;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.SubscriptionTicketPort;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenIssuer;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.config.LogLines;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthenticationWebFilterTest {

  private static final String SECRET = "test-only-secret-never-used-in-production-0123456789abcdef";

  private final LocalJwtTokenIssuer issuer = new LocalJwtTokenIssuer(SECRET);
  private final TokenValidationPort tokenValidationPort =
      new LocalJwtTokenValidationAdapter(SECRET);
  private final InMemoryTickets tickets = new InMemoryTickets();
  private final AuthenticationWebFilter filter =
      new AuthenticationWebFilter(tokenValidationPort, tickets, new RouteAuthorizationPolicy());

  private static final class InMemoryTickets implements SubscriptionTicketPort {

    private final Map<String, AuthenticatedPrincipal> stored = new ConcurrentHashMap<>();

    String issue(final String ticket, final Role role) {
      stored.put(
          SubscriptionTicketFingerprint.of(ticket),
          new AuthenticatedPrincipal("client-1", TenantId.of("tenant-a"), role));
      return ticket;
    }

    @Override
    public Mono<Void> save(
        final String fingerprint, final AuthenticatedPrincipal principal, final Instant expiresAt) {
      stored.put(fingerprint, principal);
      return Mono.empty();
    }

    @Override
    public Mono<AuthenticatedPrincipal> consume(final String fingerprint) {
      return Mono.justOrEmpty(stored.remove(fingerprint));
    }
  }

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
  void rejectsInsufficientRoleWithForbidden() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
    final ServerWebExchange exchange =
        exchangeWithHeader(HttpMethod.GET, "/notifications", "Bearer " + token);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void acceptsSufficientRoleAndContinuesChain() {
    final String operadorToken =
        issuer.issue(TenantId.of("tenant-a"), Role.OPERADOR, "client-1", Duration.ofMinutes(5));
    final ServerWebExchange operadorExchange =
        exchangeWithHeader(HttpMethod.GET, "/notifications", "Bearer " + operadorToken);
    final AtomicBoolean operadorChainInvoked = new AtomicBoolean(false);
    StepVerifier.create(filter.filter(operadorExchange, recordingChain(operadorChainInvoked)))
        .verifyComplete();
    assertTrue(operadorChainInvoked.get());

    final String adminToken =
        issuer.issue(
            TenantId.of("tenant-a"), Role.ADMINISTRADOR, "client-1", Duration.ofMinutes(5));
    final ServerWebExchange adminExchange =
        exchangeWithHeader(HttpMethod.GET, "/notifications", "Bearer " + adminToken);
    final AtomicBoolean adminChainInvoked = new AtomicBoolean(false);
    StepVerifier.create(filter.filter(adminExchange, recordingChain(adminChainInvoked)))
        .verifyComplete();
    assertTrue(adminChainInvoked.get());
  }

  @Test
  void insufficientRoleRejectionIsLoggedWithTheKnownTenant() {
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      final String token =
          issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
      final ServerWebExchange exchange =
          exchangeWithHeader(HttpMethod.GET, "/notifications", "Bearer " + token);

      StepVerifier.create(filter.filter(exchange, recordingChain(new AtomicBoolean())))
          .verifyComplete();

      final List<String> lines = lines(logs);
      assertTrue(lines.stream().anyMatch(line -> line.contains("INSUFFICIENT_ROLE")));
      assertTrue(lines.stream().anyMatch(line -> line.contains("tenant-a")));
    } finally {
      release(logs);
    }
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
  void actuatorRoutesAreNotExemptedOnTheMainPort() {
    for (final String path :
        List.of(
            "/actuator",
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/prometheus",
            "/actuator/env")) {
      final ServerWebExchange exchange = exchange(HttpMethod.GET, path);
      final AtomicBoolean chainInvoked = new AtomicBoolean(false);

      StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(), path);
      assertFalse(chainInvoked.get(), path);
    }
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
  void subscribeWithAValidTicketResolvesThePrincipalWithoutTouchingTokenValidation() {
    final AtomicBoolean validatorCalled = new AtomicBoolean(false);
    final TokenValidationPort failingValidator =
        rawToken -> {
          validatorCalled.set(true);
          return Mono.error(new IllegalStateException("must not be called"));
        };
    final AuthenticationWebFilter ticketFilter =
        new AuthenticationWebFilter(failingValidator, tickets, new RouteAuthorizationPolicy());
    final String ticket =
        tickets.issue("valid-ticket-0000000000000000000000000000001", Role.CLIENTE);
    final ServerWebExchange exchange = subscribeExchange("ticket=" + ticket);
    final AtomicReference<Object> principal = new AtomicReference<>();
    final WebFilterChain chain =
        chainExchange -> {
          principal.set(chainExchange.getAttribute(AuthenticationWebFilter.PRINCIPAL_ATTRIBUTE));
          return Mono.empty();
        };

    StepVerifier.create(ticketFilter.filter(exchange, chain)).verifyComplete();

    assertFalse(validatorCalled.get());
    assertEquals(
        new AuthenticatedPrincipal("client-1", TenantId.of("tenant-a"), Role.CLIENTE),
        principal.get());
  }

  @Test
  void theSameTicketCannotBeUsedTwice() {
    final String ticket = tickets.issue("single-use-ticket-000000000000000000000001", Role.CLIENTE);

    final AtomicBoolean firstInvoked = new AtomicBoolean(false);
    StepVerifier.create(
            filter.filter(subscribeExchange("ticket=" + ticket), recordingChain(firstInvoked)))
        .verifyComplete();
    assertTrue(firstInvoked.get());

    final ServerWebExchange second = subscribeExchange("ticket=" + ticket);
    final AtomicBoolean secondInvoked = new AtomicBoolean(false);
    StepVerifier.create(filter.filter(second, recordingChain(secondInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, second.getResponse().getStatusCode());
    assertFalse(secondInvoked.get());
  }

  @Test
  void anUnknownOrBlankOrMissingTicketIsRejectedWithUnauthorized() {
    for (final String query : List.of("ticket=never-issued", "ticket=", "ticket=%20", "")) {
      final ServerWebExchange exchange = subscribeExchange(query);
      final AtomicBoolean chainInvoked = new AtomicBoolean(false);

      StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(), query);
      assertFalse(chainInvoked.get(), query);
    }
  }

  @Test
  void aTicketIsOnlyHonouredOnTheSubscribeRoute() {
    final String ticket = tickets.issue("route-bound-ticket-0000000000000000000001", Role.CLIENTE);
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/notifications?ticket=" + ticket).build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void accessTokenQueryParamIsNoLongerAcceptedOnSubscribe() {
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
    final ServerWebExchange exchange = subscribeExchange("access_token=" + token);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void headerTakesPriorityOverTheTicketQueryParam() {
    final String validToken =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
    final String ticket = tickets.issue("unused-ticket-00000000000000000000000001", Role.CLIENTE);
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications:subscribe?ticket=" + ticket)
                .header("Authorization", "Bearer " + validToken)
                .build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertTrue(chainInvoked.get());
    StepVerifier.create(tickets.consume(SubscriptionTicketFingerprint.of(ticket)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void anInvalidHeaderIsRejectedEvenWhenAValidTicketIsPresent() {
    final String ticket = tickets.issue("shadowed-ticket-000000000000000000000001", Role.CLIENTE);
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications:subscribe?ticket=" + ticket)
                .header("Authorization", "Bearer not-a-jwt")
                .build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void aRejectedTicketIsNeverWrittenToTheLog() {
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      final String ticket = "leaky-ticket-candidate-0000000000000000000001";
      final ServerWebExchange exchange = subscribeExchange("ticket=" + ticket);

      StepVerifier.create(filter.filter(exchange, recordingChain(new AtomicBoolean())))
          .verifyComplete();

      final List<String> lines = lines(logs);
      assertTrue(
          lines.stream().anyMatch(line -> line.contains("Request rejected by authentication")));
      assertTrue(lines.stream().noneMatch(line -> line.contains(ticket)));
    } finally {
      release(logs);
    }
  }

  @Test
  void anErrorRaisedDownstreamAfterAuthenticationIsNotTreatedAsACredentialRejection() {
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      final String token =
          issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
      final ServerWebExchange exchange =
          exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer " + token);
      final WebFilterChain failingChain =
          chainExchange -> Mono.error(new IllegalStateException("downstream failure"));

      StepVerifier.create(filter.filter(exchange, failingChain))
          .expectError(IllegalStateException.class)
          .verify();

      assertNull(exchange.getResponse().getStatusCode());
      assertTrue(
          lines(logs).stream()
              .noneMatch(line -> line.contains("Request rejected by authentication")));
    } finally {
      release(logs);
    }
  }

  @Test
  void anInvalidTokenIsStillRejectedWithUnauthorizedAndLogged() {
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      final ServerWebExchange exchange =
          exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer not-a-jwt");

      StepVerifier.create(filter.filter(exchange, recordingChain(new AtomicBoolean())))
          .verifyComplete();

      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
      assertTrue(
          lines(logs).stream()
              .anyMatch(line -> line.contains("Request rejected by authentication")));
    } finally {
      release(logs);
    }
  }

  @Test
  void routesThatOnlyShareAPrefixWithAnExemptionRequireAuthentication() {
    for (final String path :
        List.of("/actuatorX", "/openapiX", "/swagger-uiX", "/v3/api-docsX", "/actuator-private")) {
      final ServerWebExchange exchange = exchange(HttpMethod.GET, path);
      final AtomicBoolean chainInvoked = new AtomicBoolean(false);

      StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(), path);
      assertFalse(chainInvoked.get(), path);
    }
  }

  @Test
  void actuatorRoutesAreExemptedOnlyInTheManagementContext() {
    final ApplicationContext management =
        new GenericApplicationContext(new GenericApplicationContext());
    for (final String path : List.of("/actuator/health", "/actuator/prometheus")) {
      final ServerWebExchange exchange = inContext(exchange(HttpMethod.GET, path), management);
      final AtomicBoolean chainInvoked = new AtomicBoolean(false);

      StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

      assertTrue(chainInvoked.get(), path);
    }
  }

  @Test
  void theBusinessRoutesStayProtectedInTheManagementContext() {
    final ApplicationContext management =
        new GenericApplicationContext(new GenericApplicationContext());
    final ServerWebExchange exchange =
        inContext(exchange(HttpMethod.GET, "/notifications"), management);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void actuatorRoutesAreNotExemptedInARootContext() {
    final ApplicationContext root = new GenericApplicationContext();
    final ServerWebExchange exchange =
        inContext(exchange(HttpMethod.GET, "/actuator/health"), root);
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  private static ServerWebExchange inContext(
      final ServerWebExchange exchange, final ApplicationContext context) {
    return new ServerWebExchangeDecorator(exchange) {
      @Override
      public ApplicationContext getApplicationContext() {
        return context;
      }
    };
  }

  @Test
  void exemptsTheDocumentationRoutesBySegment() {
    for (final String path :
        List.of(
            "/openapi/api-notificaciones.yaml",
            "/swagger-ui.html",
            "/swagger-ui/index.html",
            "/v3/api-docs/swagger-config")) {
      final ServerWebExchange exchange = exchange(HttpMethod.GET, path);
      final AtomicBoolean chainInvoked = new AtomicBoolean(false);

      StepVerifier.create(filter.filter(exchange, recordingChain(chainInvoked))).verifyComplete();

      assertTrue(chainInvoked.get(), path);
    }
  }

  @Test
  void rejectionsOfVerifiedTokensAreLoggedWithTheirReasonAndTheTenant() {
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      final String unknownRole =
          Jwts.builder()
              .subject("client-1")
              .claim("tenantId", "tenant-a")
              .claim("role", "SUPERADMIN")
              .expiration(new Date(System.currentTimeMillis() + 60_000))
              .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
              .compact();
      final String wrongTenantType =
          Jwts.builder()
              .subject("client-1")
              .claim("tenantId", 42)
              .claim("role", "CLIENTE")
              .expiration(new Date(System.currentTimeMillis() + 60_000))
              .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
              .compact();
      final String noExpiration =
          Jwts.builder()
              .subject("client-1")
              .claim("tenantId", "tenant-b")
              .claim("role", "CLIENTE")
              .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
              .compact();
      for (final String token : List.of(unknownRole, wrongTenantType, noExpiration)) {
        StepVerifier.create(
                filter.filter(
                    exchangeWithHeader(HttpMethod.POST, "/notifications", "Bearer " + token),
                    recordingChain(new AtomicBoolean())))
            .verifyComplete();
      }

      final List<String> lines = lines(logs);
      assertTrue(
          lines.stream().anyMatch(l -> l.contains("UNKNOWN_ROLE") && l.contains("tenant-a")));
      assertTrue(lines.stream().anyMatch(l -> l.contains("MISSING_CLAIMS")));
      assertTrue(
          lines.stream().anyMatch(l -> l.contains("MISSING_EXPIRATION") && l.contains("tenant-b")));
    } finally {
      release(logs);
    }
  }

  private static ServerWebExchange subscribeExchange(final String query) {
    final String uri =
        query.isEmpty() ? "/notifications:subscribe" : "/notifications:subscribe?" + query;
    return MockServerWebExchange.from(MockServerHttpRequest.get(uri).build());
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
    return appender.list.stream().map(LogLines::render).toList();
  }
}
