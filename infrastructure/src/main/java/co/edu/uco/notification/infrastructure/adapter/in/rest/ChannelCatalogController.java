package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.QueryChannelCatalogUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
  public Mono<ChannelCatalogResponse> listChannels(
      @RequestHeader(name = "X-Tenant-Id", required = false) final String tenantId) {
    return requireTenant(tenantId)
        .flatMap(tenant -> queryChannelCatalogUseCase.listChannels())
        .map(ChannelCatalogResponse::from);
  }

  @GetMapping("/providers")
  public Mono<ProviderCatalogResponse> listProviders(
      @RequestHeader(name = "X-Tenant-Id", required = false) final String tenantId) {
    return requireTenant(tenantId)
        .flatMap(tenant -> queryChannelCatalogUseCase.listProviders())
        .map(ProviderCatalogResponse::from);
  }

  private static Mono<TenantId> requireTenant(final String tenantId) {
    return Mono.fromCallable(() -> TenantId.of(tenantId));
  }
}
