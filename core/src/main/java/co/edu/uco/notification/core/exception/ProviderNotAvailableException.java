package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;

public class ProviderNotAvailableException extends RuntimeException {

  public ProviderNotAvailableException(final ProviderId providerId) {
    super("No notification sender registered for provider: " + providerId.value());
  }
}
