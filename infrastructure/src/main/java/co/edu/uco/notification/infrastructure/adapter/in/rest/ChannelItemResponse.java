package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ChannelView;
import java.util.List;

public record ChannelItemResponse(
    String channelType, String contentSchema, List<ChannelProviderItemResponse> providers) {

  public ChannelItemResponse {
    providers = List.copyOf(providers);
  }

  static ChannelItemResponse from(final ChannelView view) {
    return new ChannelItemResponse(
        view.channelType().value(),
        view.contentSchema(),
        view.providers().stream().map(ChannelProviderItemResponse::from).toList());
  }
}
