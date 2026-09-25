package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record ChannelView(
    ChannelType channelType, String contentSchema, List<ChannelProviderView> providers) {

  public ChannelView {
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(providers, "providers must not be null");
    providers = List.copyOf(providers);
  }
}
