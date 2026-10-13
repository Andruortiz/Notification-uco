package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record ProviderView(
    ProviderId providerId,
    ProviderStatus status,
    String statusReason,
    List<ProviderChannelView> channels) {

  public ProviderView {
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireTrue(
        (status == ProviderStatus.ENABLED) == (statusReason == null),
        "statusReason must be present exactly when the provider is not enabled");
    Preconditions.requireNonNull(channels, "channels must not be null");
    channels = List.copyOf(channels);
  }
}
