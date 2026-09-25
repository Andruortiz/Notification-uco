package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ProviderView;
import java.util.List;

public record ProviderItemResponse(
    String providerId,
    String status,
    String statusReason,
    List<ProviderChannelItemResponse> channels) {

  public ProviderItemResponse {
    channels = List.copyOf(channels);
  }

  static ProviderItemResponse from(final ProviderView view) {
    return new ProviderItemResponse(
        view.providerId().value(),
        view.status().name(),
        view.statusReason(),
        view.channels().stream().map(ProviderChannelItemResponse::from).toList());
  }
}
