package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenIssuer;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.TraceParent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CorrelationIdWebFilterTest {

  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
  private static final String SECRET = "test-only-secret-never-used-in-production-0123456789abcdef";

  private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

  @Test
  void reusesAValidIncomingHeaderAndEchoesItInTheResponse() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications").header(CorrelationId.HEADER, "req-123"));
    final AtomicReference<String> seen = new AtomicReference<>();

    StepVerifier.create(filter.filter(exchange, capturing(seen))).verifyComplete();

    assertEquals("req-123", seen.get());
    assertEquals("req-123", exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER));
  }

  @Test
  void generatesAnIdWhenTheHeaderIsMissing() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/notifications"));
    final AtomicReference<String> seen = new AtomicReference<>();

    StepVerifier.create(filter.filter(exchange, capturing(seen))).verifyComplete();

    assertNotNull(seen.get());
    assertEquals(seen.get(), exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER));
  }

  @Test
  void replacesAnInvalidIncomingHeader() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications")
                .header(CorrelationId.HEADER, "not valid value"));
    final AtomicReference<String> seen = new AtomicReference<>();

    StepVerifier.create(filter.filter(exchange, capturing(seen))).verifyComplete();

    assertNotEquals("not valid value", seen.get());
  }

  @Test
  void replacesAnIdWithControlCharactersAndNeverEchoesIt() {
    final String injected = "abc\nforged-entry";
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications").header(CorrelationId.HEADER, injected));
    final AtomicReference<String> seen = new AtomicReference<>();

    StepVerifier.create(filter.filter(exchange, capturing(seen))).verifyComplete();

    assertFalse(seen.get().contains("\n"));
    assertNotEquals(injected, seen.get());
    assertNotEquals(injected, exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER));
  }

  @Test
  void replacesAnIdLongerThanTheMaximum() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications")
                .header(CorrelationId.HEADER, "x".repeat(65)));
    final AtomicReference<String> seen = new AtomicReference<>();

    StepVerifier.create(filter.filter(exchange, capturing(seen))).verifyComplete();

    assertTrue(seen.get().length() <= 64);
    assertNotEquals("x".repeat(65), seen.get());
  }

  @Test
  void aValidTraceparentIsExposedInTheContextAndEchoedInTheResponse() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications").header(TraceParent.HEADER, TRACEPARENT));
    final AtomicReference<String> seen = new AtomicReference<>();

    StepVerifier.create(filter.filter(exchange, capturingTrace(seen))).verifyComplete();

    assertEquals(TRACEPARENT, seen.get());
    assertEquals(TRACEPARENT, exchange.getResponse().getHeaders().getFirst(TraceParent.HEADER));
  }

  @Test
  void anInvalidOrMissingTraceparentIsDroppedWithoutGeneratingOne() {
    final MockServerWebExchange invalid =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications").header(TraceParent.HEADER, "garbage"));
    final MockServerWebExchange missing =
        MockServerWebExchange.from(MockServerHttpRequest.get("/notifications"));
    final AtomicReference<String> seenInvalid = new AtomicReference<>();
    final AtomicReference<String> seenMissing = new AtomicReference<>();

    StepVerifier.create(filter.filter(invalid, capturingTrace(seenInvalid))).verifyComplete();
    StepVerifier.create(filter.filter(missing, capturingTrace(seenMissing))).verifyComplete();

    assertNull(seenInvalid.get());
    assertNull(seenMissing.get());
    assertNull(invalid.getResponse().getHeaders().getFirst(TraceParent.HEADER));
    assertNull(missing.getResponse().getHeaders().getFirst(TraceParent.HEADER));
  }

  @Test
  void concurrentRequestsEachKeepTheirOwnIdInTheContext() {
    final int requests = 100;

    final List<String> mismatches =
        Flux.range(0, requests)
            .flatMap(
                index -> {
                  final String id = "req-" + index;
                  final MockServerWebExchange exchange =
                      MockServerWebExchange.from(
                          MockServerHttpRequest.get("/notifications")
                              .header(CorrelationId.HEADER, id));
                  final AtomicReference<String> seen = new AtomicReference<>();
                  return filter
                      .filter(exchange, delayedCapturing(seen))
                      .then(
                          Mono.fromSupplier(
                              () -> id.equals(seen.get()) ? "" : id + "!=" + seen.get()));
                },
                32)
            .filter(result -> !result.isEmpty())
            .collectList()
            .block(Duration.ofSeconds(20));

    assertEquals(new ArrayList<String>(), mismatches);
  }

  @Test
  void aRejectedRequestStillCarriesTheCorrelationIdInHeaderAndBody() {
    final AuthenticationWebFilter authentication =
        new AuthenticationWebFilter(
            new LocalJwtTokenValidationAdapter(SECRET),
            org.mockito.Mockito.mock(
                co.edu.uco.notification.core.port.out.SubscriptionTicketPort.class),
            new RouteAuthorizationPolicy());
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/notifications").header(CorrelationId.HEADER, "req-401"));
    final WebFilterChain chain =
        webExchange -> authentication.filter(webExchange, ex -> Mono.error(new AssertionError()));

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertEquals("req-401", exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER));
    final String body = exchange.getResponse().getBodyAsString().block();
    assertTrue(body.contains("\"correlationId\":\"req-401\""), body);
  }

  @Test
  void aRoleRejectionAlsoCarriesTheCorrelationIdAndTheTenantIsAddedToTheContext() {
    final AuthenticationWebFilter authentication =
        new AuthenticationWebFilter(
            new LocalJwtTokenValidationAdapter(SECRET),
            org.mockito.Mockito.mock(
                co.edu.uco.notification.core.port.out.SubscriptionTicketPort.class),
            new RouteAuthorizationPolicy());
    final String token =
        new LocalJwtTokenIssuer(SECRET)
            .issue(TenantId.of("tenant-z"), Role.CLIENTE, "client-1", Duration.ofMinutes(5));
    final MockServerWebExchange forbidden =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/channels:register")
                .header("Authorization", "Bearer " + token)
                .header(CorrelationId.HEADER, "req-403"));
    final WebFilterChain chain =
        webExchange -> authentication.filter(webExchange, ex -> Mono.empty());

    StepVerifier.create(filter.filter(forbidden, chain)).verifyComplete();

    assertEquals(HttpStatus.FORBIDDEN, forbidden.getResponse().getStatusCode());
    assertEquals("req-403", forbidden.getResponse().getHeaders().getFirst(CorrelationId.HEADER));
    assertTrue(
        forbidden
            .getResponse()
            .getBodyAsString()
            .block()
            .contains("\"correlationId\":\"req-403\""));

    final MockServerWebExchange allowed =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/notifications/abc")
                .header("Authorization", "Bearer " + token));
    final AtomicReference<Object> tenantSeen = new AtomicReference<>();
    final WebFilterChain capturingTenant =
        webExchange ->
            authentication.filter(
                webExchange,
                ex ->
                    Mono.deferContextual(
                        context -> {
                          tenantSeen.set(context.getOrDefault("tenantId", null));
                          return Mono.empty();
                        }));

    StepVerifier.create(filter.filter(allowed, capturingTenant)).verifyComplete();

    assertEquals("tenant-z", tenantSeen.get());
  }

  private static WebFilterChain capturing(final AtomicReference<String> seen) {
    return exchange ->
        Mono.deferContextual(
            context -> {
              seen.set(context.get(CorrelationId.CONTEXT_KEY));
              return Mono.empty();
            });
  }

  private static WebFilterChain capturingTrace(final AtomicReference<String> seen) {
    return exchange ->
        Mono.deferContextual(
            context -> {
              seen.set(context.getOrDefault(TraceParent.CONTEXT_KEY, null));
              return Mono.empty();
            });
  }

  private static WebFilterChain delayedCapturing(final AtomicReference<String> seen) {
    return exchange ->
        Mono.delay(Duration.ofMillis(5))
            .then(
                Mono.deferContextual(
                    context -> {
                      seen.set(context.get(CorrelationId.CONTEXT_KEY));
                      return Mono.empty();
                    }));
  }
}
