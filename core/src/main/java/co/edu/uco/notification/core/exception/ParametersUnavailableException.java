package co.edu.uco.notification.core.exception;

public class ParametersUnavailableException extends RuntimeException {

  public ParametersUnavailableException(final String message) {
    super(message);
  }

  public ParametersUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
