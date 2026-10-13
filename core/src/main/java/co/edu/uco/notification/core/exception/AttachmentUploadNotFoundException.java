package co.edu.uco.notification.core.exception;

public class AttachmentUploadNotFoundException extends RuntimeException {

  public AttachmentUploadNotFoundException() {
    super("attachment upload not found");
  }
}
