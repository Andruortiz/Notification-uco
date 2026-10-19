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
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AuthenticationUnavailableException;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.SessionIdentityPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenIssuer;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthenticationWebFilterSessionTest {

  private static final String SECRET = "test-only-secret-never-used-in-production-0123456789abcdef";
  private static final String COOKIE = "SECURITY_BASELINE_SESSION";

  private final LocalJwtTokenIssuer issuer = new LocalJwtTokenIssuer(SECRET);
  private final Map<String, AuthenticatedPrincipal> sessions = new ConcurrentHashMap<>();
  private final AtomicInteger resolutions = new AtomicInteger();
  private final AtomicReference<Throwable> platformFailure = new AtomicReference<>();
  private final SessionIdentityPort sessionPort =
      credential -> {
        resolutions.incrementAndGet();
        if (platformFailure.get() != null) {
          return Mono.error(platformFailure.get());
        }
        final AuthenticatedPrincipal principal = sessions.get(credential.value());
        return principal == null
            ? Mono.error(
                new InvalidTokenException(
                    "unknown session", InvalidTokenException.Reason.INVALID_SESSION, null, null))
            : Mono.just(principal);
      };
  private final AuthenticationWebFilter filter =
      new AuthenticationWebFilter(
          new LocalJwtTokenValidationAdapter(SECRET),
          new RouteAuthorizationPolicy(
              new MockEnvironment().withProperty("security.pep.enforcement.enabled", "true")),
          sessionPort);

  private static AuthenticatedPrincipal principal(final String tenant) {
    return new AuthenticatedPrincipal("user-" + tenant, TenantId.of(tenant), Role.CLIENTE);
  }

  private static MockServerHttpRequest.BaseBuilder<?> request(
      final HttpMethod method, final String path) {
    return MockServerHttpRequest.method(method, path);
  }

  private static ServerWebExchange cookieExchange(final String value) {
    return MockServerWebExchange.from(
        request(HttpMethod.GET, "/notifications").cookie(new HttpCookie(COOKIE, value)).build());
  }

  private AtomicReference<Object> run(final ServerWebExchange exchange) {
    final AtomicReference<Object> principal = new AtomicReference<>();
    final WebFilterChain chain =
        chainExchange -> {
          principal.set(chainExchange.getAttribute(AuthenticationWebFilter.PRINCIPAL_ATTRIBUTE));
          return Mono.empty();
        };
    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    return principal;
  }

  @Test
  void aValidSessionCookieContinuesTheChainWithThePrincipalOfThatSession() {
    sessions.put("cookie-a", principal("tenant-a"));

    final ServerWebExchange exchange = cookieExchange("cookie-a");
    final AtomicReference<Object> principal = run(exchange);

    assertEquals(principal("tenant-a"), principal.get());
    assertNull(exchange.getResponse().getStatusCode());
  }

  @Test
  void twoCookiesOfDifferentTenantsResolveToTheirOwnTenant() {
    sessions.put("cookie-a", principal("tenant-a"));
    sessions.put("cookie-b", principal("tenant-b"));

    assertEquals(principal("tenant-b"), run(cookieExchange("cookie-b")).get());
    assertEquals(principal("tenant-a"), run(cookieExchange("cookie-a")).get());
  }

  @Test
  void theSessionCookieIsFoundAmongOtherCookies() {
    sessions.put("cookie-a", principal("tenant-a"));
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications")
                .cookie(new HttpCookie("theme", "dark"))
                .cookie(new HttpCookie(COOKIE, "cookie-a"))
                .build());

    assertEquals(principal("tenant-a"), run(exchange).get());
  }

  @Test
  void anUnknownRevokedOrExpiredSessionIsRejectedWithUnauthorized() {
    final ServerWebExchange exchange = cookieExchange("never-issued");
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(
            filter.filter(
                exchange,
                chainExchange -> {
                  chainInvoked.set(true);
                  return Mono.empty();
                }))
        .verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void anUnavailablePlatformAnswers503WithTheDedicatedCodeAndNeverRunsTheChain() {
    platformFailure.set(new AuthenticationUnavailableException("down"));
    final ServerWebExchange exchange = cookieExchange("cookie-a");
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(
            filter.filter(
                exchange,
                chainExchange -> {
                  chainInvoked.set(true);
                  return Mono.empty();
                }))
        .verifyComplete();

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
  }

  @Test
  void anUnexpectedFailureOfThePortIsTreatedAsUnavailable() {
    platformFailure.set(new IllegalStateException("boom"));
    final ServerWebExchange exchange = cookieExchange("cookie-a");

    run(exchange);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
  }

  @Test
  void aBlankOrSimilarlyNamedCookieIsNotASession() {
    sessions.put("cookie-a", principal("tenant-a"));
    final ServerWebExchange blank =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications").cookie(new HttpCookie(COOKIE, "")).build());
    final ServerWebExchange similar =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications")
                .cookie(new HttpCookie("X" + COOKIE, "cookie-a"))
                .build());

    run(blank);
    run(similar);

    assertEquals(HttpStatus.UNAUTHORIZED, blank.getResponse().getStatusCode());
    assertEquals(HttpStatus.UNAUTHORIZED, similar.getResponse().getStatusCode());
    assertEquals(0, resolutions.get());
  }

  @Test
  void aRequestWithoutAnyCredentialIsRejectedWithoutConsultingTheSessionPlatform() {
    final ServerWebExchange exchange =
        MockServerWebExchange.from(request(HttpMethod.GET, "/notifications").build());

    run(exchange);

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertEquals(0, resolutions.get());
  }

  @Test
  void aValidBearerTakesPrecedenceOverAValidCookieOfAnotherTenant() {
    sessions.put("cookie-b", principal("tenant-b"));
    final String token =
        issuer.issue(TenantId.of("tenant-a"), Role.CLIENTE, "bearer-user", Duration.ofMinutes(5));
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications")
                .header("Authorization", "Bearer " + token)
                .cookie(new HttpCookie(COOKIE, "cookie-b"))
                .build());

    final AuthenticatedPrincipal resolved = (AuthenticatedPrincipal) run(exchange).get();

    assertEquals(TenantId.of("tenant-a"), resolved.tenantId());
    assertEquals(0, resolutions.get());
  }

  @Test
  void anInvalidBearerIsRejectedEvenWhenTheCookieIsValid() {
    sessions.put("cookie-a", principal("tenant-a"));
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications")
                .header("Authorization", "Bearer not-a-jwt")
                .cookie(new HttpCookie(COOKIE, "cookie-a"))
                .build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(
            filter.filter(
                exchange,
                chainExchange -> {
                  chainInvoked.set(true);
                  return Mono.empty();
                }))
        .verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(chainInvoked.get());
    assertEquals(0, resolutions.get());
  }

  @Test
  void aNonBearerAuthorizationHeaderIsNotReplacedByTheCookie() {
    sessions.put("cookie-a", principal("tenant-a"));
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications")
                .header("Authorization", "Basic abc")
                .cookie(new HttpCookie(COOKIE, "cookie-a"))
                .build());

    run(exchange);

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
  }

  @Test
  void theStreamWithATicketAndNoBearerOrCookieIsRejectedWithoutConsultingTheSessionPort() {
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications/stream?ticket=any-ticket-value").build());

    run(exchange);

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertEquals(0, resolutions.get());
  }

  @Test
  void theSubscriptionAuthenticatesWithTheCookieWhenThereIsNoBearer() {
    sessions.put("cookie-a", principal("tenant-a"));
    final ServerWebExchange exchange =
        MockServerWebExchange.from(
            request(HttpMethod.GET, "/notifications/stream")
                .cookie(new HttpCookie(COOKIE, "cookie-a"))
                .build());

    assertEquals(principal("tenant-a"), run(exchange).get());
  }

  @Test
  void preflightRequestsStayExemptWithoutCookie() {
    final ServerWebExchange exchange =
        MockServerWebExchange.from(request(HttpMethod.OPTIONS, "/notifications").build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(
            filter.filter(
                exchange,
                chainExchange -> {
                  chainInvoked.set(true);
                  return Mono.empty();
                }))
        .verifyComplete();

    assertTrue(chainInvoked.get());
  }

  @Test
  void withoutTheSessionPortTheConstructorOfTwoArgumentsRejectsAnyCookie() {
    final AuthenticationWebFilter legacy =
        new AuthenticationWebFilter(
            new LocalJwtTokenValidationAdapter(SECRET), new RouteAuthorizationPolicy());
    final ServerWebExchange exchange = cookieExchange("cookie-a");

    StepVerifier.create(legacy.filter(exchange, chainExchange -> Mono.empty())).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
  }

  @Test
  void rejectionsAreLoggedWithTheirReasonAndNeverWithTheCookieValue() {
    final ListAppender<ILoggingEvent> logs = new ConcurrentListAppender();
    logs.start();
    final Logger logger = (Logger) LoggerFactory.getLogger(AuthenticationWebFilter.class);
    logger.addAppender(logs);
    try {
      run(cookieExchange("secret-cookie-value-1"));
      platformFailure.set(new AuthenticationUnavailableException("down"));
      run(cookieExchange("secret-cookie-value-2"));

      final List<String> lines = logs.list.stream().map(LogLines::render).toList();
      assertTrue(lines.stream().anyMatch(line -> line.contains("INVALID_SESSION")));
      assertTrue(lines.stream().anyMatch(line -> line.contains("SESSION_UNAVAILABLE")));
      assertTrue(lines.stream().anyMatch(line -> line.contains("NTF-2003")));
      assertTrue(lines.stream().noneMatch(line -> line.contains("secret-cookie-value")));
    } finally {
      logger.detachAppender(logs);
    }
  }
}
