package co.edu.uco.notification.core.exception;

public class AuthenticationUnavailableException extends RuntimeException {

  public AuthenticationUnavailableException(final String message) {
    super(message);
  }

  public AuthenticationUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
