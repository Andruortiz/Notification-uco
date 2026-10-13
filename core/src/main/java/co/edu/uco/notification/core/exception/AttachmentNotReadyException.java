package co.edu.uco.notification.core.exception;

public class AttachmentNotReadyException extends RuntimeException {

  private final int position;

  public AttachmentNotReadyException(final int position) {
    super("attachments[" + position + "]: the upload is still being scanned; retry later");
    this.position = position;
  }

  public int position() {
    return position;
  }
}
