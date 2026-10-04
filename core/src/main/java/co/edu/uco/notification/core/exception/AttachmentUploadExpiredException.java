package co.edu.uco.notification.core.exception;

public class AttachmentUploadExpiredException extends RuntimeException {

  public AttachmentUploadExpiredException() {
    super("the upload expired; request a new one");
  }
}
