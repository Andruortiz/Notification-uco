package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.port.in.QueryConfigurationUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ConfigurationController {

  private final QueryConfigurationUseCase queryConfigurationUseCase;

  public ConfigurationController(final QueryConfigurationUseCase queryConfigurationUseCase) {
    this.queryConfigurationUseCase =
        Preconditions.requireNonNull(
            queryConfigurationUseCase, "queryConfigurationUseCase must not be null");
  }

  @GetMapping("/configuration")
  public Mono<ConfigurationResponse> getConfiguration(final AuthenticatedPrincipal principal) {
    return queryConfigurationUseCase.describe().map(ConfigurationResponse::from);
  }
}
