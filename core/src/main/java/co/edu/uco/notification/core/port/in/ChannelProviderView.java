package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.Preconditions;

public record ChannelProviderView(
    ProviderId providerId, int preferenceOrder, ProviderStatus status, String statusReason) {

  public ChannelProviderView {
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    Preconditions.requireTrue(preferenceOrder >= 1, "preferenceOrder must be at least 1");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireTrue(
        (status == ProviderStatus.ENABLED) == (statusReason == null),
        "statusReason must be present exactly when the provider is not enabled");
  }
}
