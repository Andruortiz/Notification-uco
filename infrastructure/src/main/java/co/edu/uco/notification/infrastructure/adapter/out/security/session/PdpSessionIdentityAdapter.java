package co.edu.uco.notification.infrastructure.adapter.out.security.session;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.SessionCredential;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AuthenticationUnavailableException;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.SessionIdentityPort;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public class PdpSessionIdentityAdapter implements SessionIdentityPort {

  static final String COOKIE_NAME = "SECURITY_BASELINE_SESSION";

  private final WebClient webClient;
  private final String path;
  private final Duration timeout;

  public PdpSessionIdentityAdapter(
      final WebClient webClient, final String path, final Duration timeout) {
    this.webClient = Preconditions.requireNonNull(webClient, "webClient must not be null");
    this.path = Preconditions.requireNonBlank(path, "path must not be blank");
    this.timeout = Preconditions.requireNonNull(timeout, "timeout must not be null");
  }

  @Override
  public Mono<AuthenticatedPrincipal> resolve(final SessionCredential credential) {
    return Mono.defer(
            () ->
                webClient
                    .get()
                    .uri(path)
                    .header(HttpHeaders.COOKIE, COOKIE_NAME + "=" + credential.value())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(this::toPrincipal)
                    .timeout(timeout))
        .onErrorMap(
            error ->
                !(error instanceof InvalidTokenException
                    || error instanceof AuthenticationUnavailableException),
            error ->
                new AuthenticationUnavailableException(
                    "session platform failed: " + error.getClass().getSimpleName(), error));
  }

  private Mono<AuthenticatedPrincipal> toPrincipal(final ClientResponse response) {
    final int status = response.statusCode().value();
    if (status == 200) {
      return response
          .bodyToMono(SessionResponse.class)
          .onErrorMap(DecodingException.class, error -> invalidSession("unreadable session"))
          .map(PdpSessionIdentityAdapter::principalOf)
          .switchIfEmpty(Mono.error(invalidSession("empty session")));
    }
    if (status == 401 || status == 403) {
      return response.releaseBody().then(Mono.error(invalidSession("session rejected")));
    }
    return response
        .releaseBody()
        .then(
            Mono.error(
                new AuthenticationUnavailableException(
                    "session platform answered status " + status)));
  }

  private static AuthenticatedPrincipal principalOf(final SessionResponse response) {
    final SessionData data = response.data();
    if (data == null || isBlank(data.subject()) || isBlank(data.tenantId())) {
      throw invalidSession("session without subject or tenant");
    }
    try {
      return new AuthenticatedPrincipal(data.subject(), TenantId.of(data.tenantId()), Role.CLIENTE);
    } catch (final IllegalArgumentException e) {
      throw invalidSession("session with an invalid tenant");
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  private static InvalidTokenException invalidSession(final String message) {
    return new InvalidTokenException(
        message, InvalidTokenException.Reason.INVALID_SESSION, null, null);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SessionResponse(SessionData data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SessionData(String subject, String tenantId) {}
}
