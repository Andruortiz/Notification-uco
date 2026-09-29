package co.edu.uco.notification.core.exception;

public class InvalidAttachmentException extends RuntimeException {

  private final int position;

  public InvalidAttachmentException(final int position, final String rule) {
    super(prefix(position) + rule);
    this.position = position;
  }

  public InvalidAttachmentException(final int position, final String rule, final String fileName) {
    super(prefix(position) + rule + " (" + fileName + ")");
    this.position = position;
  }

  public int position() {
    return position;
  }

  private static String prefix(final int position) {
    return position < 0 ? "attachments: " : "attachments[" + position + "]: ";
  }
}
