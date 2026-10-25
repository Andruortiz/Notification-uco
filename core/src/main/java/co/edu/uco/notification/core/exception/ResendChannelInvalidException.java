package co.edu.uco.notification.core.exception;

public class ResendChannelInvalidException extends RuntimeException {

  public ResendChannelInvalidException(final String reason) {
    super(reason);
  }
}
