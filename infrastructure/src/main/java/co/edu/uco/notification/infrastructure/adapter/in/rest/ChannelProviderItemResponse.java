package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ChannelProviderView;

public record ChannelProviderItemResponse(
    String providerId, int preferenceOrder, String status, String statusReason) {

  static ChannelProviderItemResponse from(final ChannelProviderView view) {
    return new ChannelProviderItemResponse(
        view.providerId().value(),
        view.preferenceOrder(),
        view.status().name(),
        view.statusReason());
  }
}
