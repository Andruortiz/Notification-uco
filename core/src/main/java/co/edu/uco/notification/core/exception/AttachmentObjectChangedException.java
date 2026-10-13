package co.edu.uco.notification.core.exception;

public class AttachmentObjectChangedException extends RuntimeException {

  public AttachmentObjectChangedException() {
    super("the uploaded file changed while it was being scanned");
  }
}
