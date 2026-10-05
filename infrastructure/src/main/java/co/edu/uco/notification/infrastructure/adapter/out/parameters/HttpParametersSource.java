package co.edu.uco.notification.infrastructure.adapter.out.parameters;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.exception.ParametersUnavailableException;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public class HttpParametersSource implements ParametersSourcePort {

  static final String STATE_PATH = "/notification-service/configuration";

  private final WebClient webClient;
  private final Duration timeout;

  public HttpParametersSource(final WebClient webClient, final Duration timeout) {
    this.webClient = Preconditions.requireNonNull(webClient, "webClient must not be null");
    this.timeout = Preconditions.requireNonNull(timeout, "timeout must not be null");
  }

  @Override
  public Mono<ConfigurationChange> fetchState() {
    return Mono.defer(
            () ->
                webClient
                    .get()
                    .uri(STATE_PATH)
                    .exchangeToMono(
                        response -> {
                          if (response.statusCode().value() != HttpStatus.OK.value()) {
                            return response
                                .releaseBody()
                                .then(
                                    Mono.error(
                                        new ParametersUnavailableException(
                                            "parameters source answered status "
                                                + response.statusCode().value())));
                          }
                          return response.bodyToMono(StateResponse.class);
                        })
                    .timeout(timeout)
                    .switchIfEmpty(
                        Mono.error(
                            new ParametersUnavailableException(
                                "parameters source returned an empty body")))
                    .map(HttpParametersSource::toChange))
        .onErrorMap(
            error -> !(error instanceof ParametersUnavailableException),
            error ->
                new ParametersUnavailableException(
                    "parameters source failed: " + error.getClass().getSimpleName(), error));
  }

  private static ConfigurationChange toChange(final StateResponse response) {
    if (response.version() == null || response.values() == null) {
      throw new ParametersUnavailableException("parameters source returned an incomplete state");
    }
    return new ConfigurationChange(response.version(), response.values());
  }

  public record StateResponse(Long version, Map<String, Object> values) {}
}
