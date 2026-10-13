package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;

public record AttachmentRequest(
    String fileName, String contentType, Long sizeBytes, String content, String url) {

  AttachmentSubmission toSubmission() {
    return new AttachmentSubmission(fileName, contentType, sizeBytes, content, url);
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
