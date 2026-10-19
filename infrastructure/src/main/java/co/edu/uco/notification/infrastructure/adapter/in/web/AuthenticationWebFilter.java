package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.SessionCredential;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.SessionIdentityPort;
import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
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
  private static final String SESSION_COOKIE = "SECURITY_BASELINE_SESSION";
  private static final String ACTUATOR_PATH = "/actuator";

  private final TokenValidationPort tokenValidationPort;
  private final RouteAuthorizationPolicy routeAuthorizationPolicy;
  private final SessionIdentityPort sessionIdentityPort;
  private final PublicRoutes publicRoutes;

  public AuthenticationWebFilter(
      final TokenValidationPort tokenValidationPort,
      final RouteAuthorizationPolicy routeAuthorizationPolicy) {
    this(tokenValidationPort, routeAuthorizationPolicy, AuthenticationWebFilter::rejectSession);
  }

  public AuthenticationWebFilter(
      final TokenValidationPort tokenValidationPort,
      final RouteAuthorizationPolicy routeAuthorizationPolicy,
      final ObjectProvider<SessionIdentityPort> sessionIdentityPort) {
    this(
        tokenValidationPort,
        routeAuthorizationPolicy,
        sessionIdentityPort,
        PublicRoutes.documentation());
  }

  @Autowired
  public AuthenticationWebFilter(
      final TokenValidationPort tokenValidationPort,
      final RouteAuthorizationPolicy routeAuthorizationPolicy,
      final ObjectProvider<SessionIdentityPort> sessionIdentityPort,
      final ObjectProvider<PublicRoutes> publicRoutes) {
    this(
        tokenValidationPort,
        routeAuthorizationPolicy,
        sessionIdentityPort.getIfAvailable(() -> AuthenticationWebFilter::rejectSession),
        publicRoutes.getIfAvailable(PublicRoutes::documentation));
  }

  private static Mono<AuthenticatedPrincipal> rejectSession(final SessionCredential credential) {
    return Mono.error(
        new InvalidTokenException(
            "session cookie not accepted",
            InvalidTokenException.Reason.INVALID_SESSION,
            null,
            null));
  }

  public AuthenticationWebFilter(
      final TokenValidationPort tokenValidationPort,
      final RouteAuthorizationPolicy routeAuthorizationPolicy,
      final SessionIdentityPort sessionIdentityPort) {
    this(
        tokenValidationPort,
        routeAuthorizationPolicy,
        sessionIdentityPort,
        PublicRoutes.documentation());
  }

  public AuthenticationWebFilter(
      final TokenValidationPort tokenValidationPort,
      final RouteAuthorizationPolicy routeAuthorizationPolicy,
      final SessionIdentityPort sessionIdentityPort,
      final PublicRoutes publicRoutes) {
    this.publicRoutes = Preconditions.requireNonNull(publicRoutes, "publicRoutes must not be null");
    this.tokenValidationPort =
        Preconditions.requireNonNull(tokenValidationPort, "tokenValidationPort must not be null");
    this.routeAuthorizationPolicy =
        Preconditions.requireNonNull(
            routeAuthorizationPolicy, "routeAuthorizationPolicy must not be null");
    this.sessionIdentityPort =
        Preconditions.requireNonNull(sessionIdentityPort, "sessionIdentityPort must not be null");
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
    final String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header == null) {
      final SessionCredential session = sessionCredential(exchange);
      if (session != null) {
        return authenticateWithSession(exchange, chain, session);
      }
    }
    final String rawToken = bearerToken(header);
    if (rawToken == null) {
      return reject(exchange, null, RejectionReason.MISSING_TOKEN);
    }
    return finishAuthentication(
        exchange,
        chain,
        tokenValidationPort
            .validate(rawToken)
            .map(Authentication::accepted)
            .onErrorResume(error -> Mono.just(Authentication.rejected(error))));
  }

  private static SessionCredential sessionCredential(final ServerWebExchange exchange) {
    final HttpCookie cookie = exchange.getRequest().getCookies().getFirst(SESSION_COOKIE);
    return cookie == null || cookie.getValue().isBlank()
        ? null
        : new SessionCredential(cookie.getValue());
  }

  private Mono<Void> authenticateWithSession(
      final ServerWebExchange exchange,
      final WebFilterChain chain,
      final SessionCredential credential) {
    return finishAuthentication(
        exchange,
        chain,
        Mono.defer(() -> sessionIdentityPort.resolve(credential))
            .map(Authentication::accepted)
            .onErrorResume(error -> Mono.just(Authentication.rejectedSession(error))));
  }

  private Mono<Void> finishAuthentication(
      final ServerWebExchange exchange,
      final WebFilterChain chain,
      final Mono<Authentication> outcome) {
    return outcome
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

    static Authentication rejectedSession(final Throwable error) {
      if (error instanceof InvalidTokenException invalid) {
        return new Authentication(null, invalid.tenantId(), reasonOf(invalid.reason()));
      }
      return rejectedFor(RejectionReason.SESSION_UNAVAILABLE);
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
      case INVALID_SESSION -> RejectionReason.INVALID_SESSION;
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

  private static boolean isManagementContext(final ServerWebExchange exchange) {
    final ApplicationContext context = exchange.getApplicationContext();
    return context != null && context.getParent() != null;
  }

  private static boolean isActuatorPath(final String path) {
    return path.equals(ACTUATOR_PATH) || path.startsWith(ACTUATOR_PATH + "/");
  }

  private boolean isExempt(final ServerWebExchange exchange) {
    if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
      return true;
    }
    final String path = exchange.getRequest().getPath().value();
    if (isManagementContext(exchange) && isActuatorPath(path)) {
      return true;
    }
    return publicRoutes.matches(path);
  }

  private static String bearerToken(final String header) {
    return header != null && header.startsWith(BEARER_PREFIX)
        ? header.substring(BEARER_PREFIX.length())
        : null;
  }

  private Mono<Void> reject(
      final ServerWebExchange exchange, final String tenantId, final RejectionReason reason) {
    if (reason == RejectionReason.SESSION_UNAVAILABLE) {
      logRejection(exchange, tenantId, reason, ErrorCode.AUTHENTICATION_UNAVAILABLE);
      return writeJson(
          exchange,
          HttpStatus.SERVICE_UNAVAILABLE,
          ErrorCode.AUTHENTICATION_UNAVAILABLE,
          "session platform unavailable");
    }
    logRejection(exchange, tenantId, reason, ErrorCode.AUTHENTICATION_REQUIRED);
    return writeJson(
        exchange,
        HttpStatus.UNAUTHORIZED,
        ErrorCode.AUTHENTICATION_REQUIRED,
        "missing or invalid bearer token");
  }

  private Mono<Void> rejectForbidden(final ServerWebExchange exchange, final String tenantId) {
    logRejection(
        exchange, tenantId, RejectionReason.INSUFFICIENT_ROLE, ErrorCode.INSUFFICIENT_ROLE);
    return writeJson(
        exchange,
        HttpStatus.FORBIDDEN,
        ErrorCode.INSUFFICIENT_ROLE,
        "role does not satisfy the minimum role required for this operation");
  }

  private static void logRejection(
      final ServerWebExchange exchange,
      final String tenantId,
      final RejectionReason reason,
      final ErrorCode code) {
    try (LogContext ignored =
        LogContext.open(
            CorrelationId.fromOrNull(correlationIdOf(exchange)),
            tenantId == null ? null : LogSanitizer.safe(tenantId),
            null)) {
      LOGGER.warn(LogFields.failure(code, "reason", reason), "Request rejected by authentication");
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
      final ServerWebExchange exchange,
      final HttpStatus status,
      final ErrorCode code,
      final String message) {
    final ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    final String correlationId = correlationIdOf(exchange);
    response.getHeaders().set(CorrelationId.HEADER, correlationId);
    final byte[] bytes =
        ("{\"code\":\""
                + code.format()
                + "\",\"message\":\""
                + message
                + "\",\"correlationId\":\""
                + correlationId
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    final DataBuffer buffer = response.bufferFactory().wrap(bytes);
    return response.writeWith(Mono.just(buffer));
  }
}
