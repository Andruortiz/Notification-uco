package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.ProviderHealthReport;
import co.edu.uco.notification.core.port.in.GetProviderHealthUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ProviderHealthController {

  private final GetProviderHealthUseCase getProviderHealthUseCase;

  public ProviderHealthController(final GetProviderHealthUseCase getProviderHealthUseCase) {
    this.getProviderHealthUseCase =
        Preconditions.requireNonNull(
            getProviderHealthUseCase, "getProviderHealthUseCase must not be null");
  }

  @GetMapping("/provider-health")
  public Mono<ProviderHealthReport> getProviderHealth() {
    return getProviderHealthUseCase.getHealth();
  }
}
