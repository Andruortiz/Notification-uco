package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;

public class ProviderDisabledException extends RuntimeException {

  public ProviderDisabledException(final ProviderId providerId, final String reason) {
    super("Notification sender disabled for provider " + providerId.value() + ": " + reason);
  }
}
