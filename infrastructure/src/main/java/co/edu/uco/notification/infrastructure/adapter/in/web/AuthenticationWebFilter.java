package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class AuthenticationWebFilter implements WebFilter, Ordered {

  public static final String PRINCIPAL_ATTRIBUTE =
      AuthenticationWebFilter.class.getName() + ".PRINCIPAL";

  private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationWebFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String SUBSCRIBE_PATH = "/notifications:subscribe";
  private static final String ACCESS_TOKEN_PARAM = "access_token";
  private static final List<String> EXEMPT_PATHS =
      List.of("/actuator", "/v3/api-docs", "/swagger-ui", "/swagger-ui.html", "/openapi");

  private final TokenValidationPort tokenValidationPort;
  private final RouteAuthorizationPolicy routeAuthorizationPolicy;

  public AuthenticationWebFilter(
      final TokenValidationPort tokenValidationPort,
      final RouteAuthorizationPolicy routeAuthorizationPolicy) {
    this.tokenValidationPort =
        Preconditions.requireNonNull(tokenValidationPort, "tokenValidationPort must not be null");
    this.routeAuthorizationPolicy =
        Preconditions.requireNonNull(
            routeAuthorizationPolicy, "routeAuthorizationPolicy must not be null");
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    if (isExempt(exchange)) {
      return chain.filter(exchange);
    }
    final String rawToken = extractToken(exchange);
    if (rawToken == null) {
      return reject(exchange, null, RejectionReason.MISSING_TOKEN);
    }
    return tokenValidationPort
        .validate(rawToken)
        .map(Authentication::accepted)
        .onErrorResume(error -> Mono.just(Authentication.rejected(error)))
        .switchIfEmpty(
            Mono.fromSupplier(() -> Authentication.rejectedFor(RejectionReason.MALFORMED_TOKEN)))
        .flatMap(
            authentication ->
                authentication.principal() == null
                    ? reject(exchange, authentication.tenantId(), authentication.reason())
                    : continueWithPrincipal(exchange, chain, authentication.principal()));
  }

  private record Authentication(
      AuthenticatedPrincipal principal, String tenantId, RejectionReason reason) {

    static Authentication accepted(final AuthenticatedPrincipal principal) {
      return new Authentication(principal, null, null);
    }

    static Authentication rejected(final Throwable error) {
      if (error instanceof InvalidTokenException invalid) {
        return new Authentication(null, invalid.tenantId(), reasonOf(invalid.reason()));
      }
      return rejectedFor(RejectionReason.MALFORMED_TOKEN);
    }

    static Authentication rejectedFor(final RejectionReason reason) {
      return new Authentication(null, null, reason);
    }
  }

  private static RejectionReason reasonOf(final InvalidTokenException.Reason reason) {
    return switch (reason) {
      case EXPIRED -> RejectionReason.EXPIRED;
      case NOT_YET_VALID -> RejectionReason.NOT_YET_VALID;
      case INVALID_SIGNATURE -> RejectionReason.INVALID_SIGNATURE;
      case MISSING_EXPIRATION -> RejectionReason.MISSING_EXPIRATION;
      case MISSING_CLAIMS -> RejectionReason.MISSING_CLAIMS;
      case UNKNOWN_ROLE -> RejectionReason.UNKNOWN_ROLE;
      case MALFORMED -> RejectionReason.MALFORMED_TOKEN;
    };
  }

  private Mono<Void> continueWithPrincipal(
      final ServerWebExchange exchange,
      final WebFilterChain chain,
      final AuthenticatedPrincipal principal) {
    final Role minimumRole =
        routeAuthorizationPolicy.minimumRoleFor(
            exchange.getRequest().getMethod(), exchange.getRequest().getPath().value());
    if (!principal.role().satisfies(minimumRole)) {
      return rejectForbidden(exchange, principal.tenantId().value());
    }
    exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
    return chain
        .filter(exchange)
        .contextWrite(context -> context.put(LogFields.TENANT_ID, principal.tenantId().value()));
  }

  private boolean isExempt(final ServerWebExchange exchange) {
    if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
      return true;
    }
    final String path = exchange.getRequest().getPath().value();
    return EXEMPT_PATHS.stream()
        .anyMatch(exempt -> path.equals(exempt) || path.startsWith(exempt + "/"));
  }

  private String extractToken(final ServerWebExchange exchange) {
    final String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header != null) {
      return header.startsWith(BEARER_PREFIX) ? header.substring(BEARER_PREFIX.length()) : null;
    }
    if (SUBSCRIBE_PATH.equals(exchange.getRequest().getPath().value())) {
      final String queryToken = exchange.getRequest().getQueryParams().getFirst(ACCESS_TOKEN_PARAM);
      return queryToken == null || queryToken.isBlank() ? null : queryToken;
    }
    return null;
  }

  private Mono<Void> reject(
      final ServerWebExchange exchange, final String tenantId, final RejectionReason reason) {
    logRejection(exchange, tenantId, reason);
    return writeJson(exchange, HttpStatus.UNAUTHORIZED, "missing or invalid bearer token");
  }

  private Mono<Void> rejectForbidden(final ServerWebExchange exchange, final String tenantId) {
    logRejection(exchange, tenantId, RejectionReason.INSUFFICIENT_ROLE);
    return writeJson(
        exchange,
        HttpStatus.FORBIDDEN,
        "role does not satisfy the minimum role required for this operation");
  }

  private static void logRejection(
      final ServerWebExchange exchange, final String tenantId, final RejectionReason reason) {
    try (LogContext ignored =
        LogContext.open(
            CorrelationId.fromOrNull(correlationIdOf(exchange)),
            tenantId == null ? null : LogSanitizer.safe(tenantId),
            null)) {
      LOGGER.warn(LogFields.fields("reason", reason), "Request rejected by authentication");
    }
  }

  private static String correlationIdOf(final ServerWebExchange exchange) {
    final Object attribute = exchange.getAttribute(CorrelationIdWebFilter.CORRELATION_ATTRIBUTE);
    if (attribute instanceof CorrelationId correlationId) {
      return correlationId.value();
    }
    final String header = exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER);
    final CorrelationId fromHeader = CorrelationId.fromOrNull(header);
    return fromHeader != null ? fromHeader.value() : CorrelationId.newId().value();
  }

  private static Mono<Void> writeJson(
      final ServerWebExchange exchange, final HttpStatus status, final String message) {
    final ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    final String correlationId = correlationIdOf(exchange);
    response.getHeaders().set(CorrelationId.HEADER, correlationId);
    final byte[] bytes =
        ("{\"message\":\"" + message + "\",\"correlationId\":\"" + correlationId + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    final DataBuffer buffer = response.bufferFactory().wrap(bytes);
    return response.writeWith(Mono.just(buffer));
  }
}
