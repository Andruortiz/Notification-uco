package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.port.in.QueryChannelCatalogUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ChannelCatalogController {

  private final QueryChannelCatalogUseCase queryChannelCatalogUseCase;

  public ChannelCatalogController(final QueryChannelCatalogUseCase queryChannelCatalogUseCase) {
    this.queryChannelCatalogUseCase =
        Preconditions.requireNonNull(
            queryChannelCatalogUseCase, "queryChannelCatalogUseCase must not be null");
  }

  @GetMapping("/channels")
  public Mono<ChannelCatalogResponse> listChannels(final AuthenticatedPrincipal principal) {
    return queryChannelCatalogUseCase.listChannels().map(ChannelCatalogResponse::from);
  }

  @GetMapping("/providers")
  public Mono<ProviderCatalogResponse> listProviders(final AuthenticatedPrincipal principal) {
    return queryChannelCatalogUseCase.listProviders().map(ProviderCatalogResponse::from);
  }
}
