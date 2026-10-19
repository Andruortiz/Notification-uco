package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CorsCredentialsTest {

  private static final String ALLOWED = "http://localhost:5173";
  private static final String OTHER = "http://localhost:9999";

  private final CorsWebFilter filter = new CorsConfig().corsWebFilter(List.of(ALLOWED));

  private static HttpHeaders committedHeaders(final MockServerWebExchange exchange) {
    exchange.getResponse().setComplete().block();
    return exchange.getResponse().getHeaders();
  }

  private MockServerWebExchange preflight(final String origin) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.method(HttpMethod.OPTIONS, "http://localhost:8060/notifications")
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
            .build());
  }

  @Test
  void aConfiguredOriginReceivesItsExactOriginAndCredentials() {
    final MockServerWebExchange exchange = preflight(ALLOWED);

    StepVerifier.create(filter.filter(exchange, chain -> Mono.empty())).verifyComplete();

    final HttpHeaders headers = committedHeaders(exchange);
    assertEquals(ALLOWED, headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    assertEquals("true", headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  void anUnconfiguredOriginReceivesNoPermission() {
    final MockServerWebExchange exchange = preflight(OTHER);

    StepVerifier.create(filter.filter(exchange, chain -> Mono.empty())).verifyComplete();

    final HttpHeaders headers = committedHeaders(exchange);
    assertNull(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    assertNull(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  void aSimpleRequestFromAConfiguredOriginAlsoCarriesCredentialsAndExposesTheCorrelationId() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("http://localhost:8060/notifications")
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .build());
    final AtomicBoolean chainInvoked = new AtomicBoolean(false);

    StepVerifier.create(
            filter.filter(
                exchange,
                chain -> {
                  chainInvoked.set(true);
                  return Mono.empty();
                }))
        .verifyComplete();

    final HttpHeaders headers = committedHeaders(exchange);
    assertEquals("true", headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    assertEquals(ALLOWED, headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    assertEquals("X-Correlation-Id", headers.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
    assertTrue(chainInvoked.get());
  }

  @Test
  void aWildcardOriginMakesTheConfigurationFail() {
    final CorsConfig config = new CorsConfig();

    assertThrows(IllegalStateException.class, () -> config.corsWebFilter(List.of("*")));
    assertThrows(IllegalStateException.class, () -> config.corsWebFilter(List.of(ALLOWED, " * ")));
    assertThrows(
        IllegalStateException.class, () -> config.corsWebFilter(List.of("https://*.example.com")));
  }
}
