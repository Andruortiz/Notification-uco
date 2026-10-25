package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.Preconditions;

public record ProviderProbeResult(
    ProviderId providerId, ChannelType channel, long latencyMs, ProbeErrorCode errorCode) {

  public ProviderProbeResult {
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    Preconditions.requireNonNull(channel, "channel must not be null");
  }

  public boolean isOk() {
    return errorCode == null;
  }
}
