package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ChannelView;
import java.util.List;

public record ChannelCatalogResponse(List<ChannelItemResponse> items) {

  public ChannelCatalogResponse {
    items = List.copyOf(items);
  }

  static ChannelCatalogResponse from(final List<ChannelView> channels) {
    return new ChannelCatalogResponse(channels.stream().map(ChannelItemResponse::from).toList());
  }
}
