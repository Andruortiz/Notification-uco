package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ProviderView;
import java.util.List;

public record ProviderCatalogResponse(List<ProviderItemResponse> items) {

  public ProviderCatalogResponse {
    items = List.copyOf(items);
  }

  static ProviderCatalogResponse from(final List<ProviderView> providers) {
    return new ProviderCatalogResponse(providers.stream().map(ProviderItemResponse::from).toList());
  }
}
