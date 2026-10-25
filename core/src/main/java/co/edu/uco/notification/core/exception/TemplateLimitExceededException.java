package co.edu.uco.notification.core.exception;

public class TemplateLimitExceededException extends RuntimeException {

  public TemplateLimitExceededException(final String message) {
    super(message);
  }
}
