package co.edu.uco.notification.core.exception;

public class ProviderTestInProgressException extends RuntimeException {

  public ProviderTestInProgressException() {
    super("a connection test for this channel and provider is already in progress");
  }
}
