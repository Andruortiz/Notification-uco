package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.utils.Preconditions;

public record ProviderChannelView(ChannelType channelType, int preferenceOrder) {

  public ProviderChannelView {
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireTrue(preferenceOrder >= 1, "preferenceOrder must be at least 1");
  }
}
