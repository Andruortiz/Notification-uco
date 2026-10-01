package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.utils.Preconditions;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
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
        .flatMap(principal -> continueWithPrincipal(exchange, chain, principal))
        .onErrorResume(error -> reject(exchange, extractTenantId(error), classify(error)));
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
    return chain.filter(exchange);
  }

  private boolean isExempt(final ServerWebExchange exchange) {
    if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
      return true;
    }
    final String path = exchange.getRequest().getPath().value();
    return path.startsWith("/actuator")
        || path.startsWith("/v3/api-docs")
        || path.startsWith("/swagger-ui")
        || path.startsWith("/openapi");
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

  private static RejectionReason classify(final Throwable error) {
    final Throwable cause = error.getCause();
    if (cause instanceof ExpiredJwtException) {
      return RejectionReason.EXPIRED;
    }
    if (cause instanceof SignatureException) {
      return RejectionReason.INVALID_SIGNATURE;
    }
    if (cause instanceof IllegalStateException) {
      return RejectionReason.MISSING_CLAIMS;
    }
    if (cause instanceof IllegalArgumentException) {
      return RejectionReason.UNKNOWN_ROLE;
    }
    if (cause instanceof MalformedJwtException) {
      return RejectionReason.MALFORMED_TOKEN;
    }
    return RejectionReason.MALFORMED_TOKEN;
  }

  private static String extractTenantId(final Throwable error) {
    if (error.getCause() instanceof ExpiredJwtException expired) {
      final Object tenantId = expired.getClaims().get("tenantId");
      return tenantId == null ? null : tenantId.toString();
    }
    return null;
  }

  private Mono<Void> reject(
      final ServerWebExchange exchange, final String tenantId, final RejectionReason reason) {
    LOGGER.warn(AuthenticationLogFormatter.rejection(tenantId, reason));
    return writeJson(exchange, HttpStatus.UNAUTHORIZED, "missing or invalid bearer token");
  }

  private Mono<Void> rejectForbidden(final ServerWebExchange exchange, final String tenantId) {
    LOGGER.warn(AuthenticationLogFormatter.rejection(tenantId, RejectionReason.INSUFFICIENT_ROLE));
    return writeJson(
        exchange,
        HttpStatus.FORBIDDEN,
        "role does not satisfy the minimum role required for this operation");
  }

  private static Mono<Void> writeJson(
      final ServerWebExchange exchange, final HttpStatus status, final String message) {
    final ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    final byte[] bytes = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
    final DataBuffer buffer = response.bufferFactory().wrap(bytes);
    return response.writeWith(Mono.just(buffer));
  }
}
