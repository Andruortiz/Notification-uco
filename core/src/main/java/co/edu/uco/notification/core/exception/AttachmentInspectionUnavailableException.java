package co.edu.uco.notification.core.exception;

public class AttachmentInspectionUnavailableException extends RuntimeException {

  public AttachmentInspectionUnavailableException(final String message) {
    super(message);
  }

  public AttachmentInspectionUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
