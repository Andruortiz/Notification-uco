package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.Attachment;

public record AttachmentSummary(
    String fileName, String contentType, long sizeBytes, String sha256) {

  public static AttachmentSummary of(final Attachment attachment) {
    return new AttachmentSummary(
        attachment.fileName(),
        attachment.contentType(),
        attachment.sizeBytes(),
        attachment.sha256().hex());
  }
}
