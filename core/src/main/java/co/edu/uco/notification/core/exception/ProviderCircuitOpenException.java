package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;

public class ProviderCircuitOpenException extends RuntimeException {

  private final ProviderId providerId;

  public ProviderCircuitOpenException(final ProviderId providerId) {
    super("Circuit open for provider " + providerId.value());
    this.providerId = providerId;
  }

  public ProviderId providerId() {
    return providerId;
  }
}
