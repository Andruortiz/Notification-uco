package co.edu.uco.notification.core.exception;

public class InvalidTokenException extends RuntimeException {

  public InvalidTokenException(final String message) {
    super(message);
  }

  public InvalidTokenException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
