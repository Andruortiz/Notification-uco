package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.Attachment;

public record AttachmentRequest(String fileName, String contentType, Long sizeBytes, String url) {

  Attachment toDomain() {
    return Attachment.of(fileName, contentType, sizeBytes, url);
  }

  @Override
  public String toString() {
    return "AttachmentRequest[fileName="
        + fileName
        + ", contentType="
        + contentType
        + ", sizeBytes="
        + sizeBytes
        + "]";
  }
}
