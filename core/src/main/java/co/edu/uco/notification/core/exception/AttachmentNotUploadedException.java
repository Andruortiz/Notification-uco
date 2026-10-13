package co.edu.uco.notification.core.exception;

public class AttachmentNotUploadedException extends RuntimeException {

  public AttachmentNotUploadedException() {
    super("the file has not been uploaded yet");
  }
}
