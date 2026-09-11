package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record ChannelRoute(
    ChannelType channelType, List<ProviderId> providers, String contentSchema) {

  public ChannelRoute {
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(providers, "providers must not be null");
    Preconditions.requireTrue(!providers.isEmpty(), "providers must not be empty");
    providers = List.copyOf(providers);
  }

  public ProviderId preferredProvider() {
    return providers.get(0);
  }
}
