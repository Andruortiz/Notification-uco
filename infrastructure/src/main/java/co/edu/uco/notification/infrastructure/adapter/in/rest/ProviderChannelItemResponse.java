package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ProviderChannelView;

public record ProviderChannelItemResponse(String channelType, int preferenceOrder) {

  static ProviderChannelItemResponse from(final ProviderChannelView view) {
    return new ProviderChannelItemResponse(view.channelType().value(), view.preferenceOrder());
  }
}
