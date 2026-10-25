package co.edu.uco.notification.core.exception;

public class ProviderMetricsUnavailableException extends RuntimeException {

  public ProviderMetricsUnavailableException() {
    super("Provider metrics source is unavailable");
  }
}
