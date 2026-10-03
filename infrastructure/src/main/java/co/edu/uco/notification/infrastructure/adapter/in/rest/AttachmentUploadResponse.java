package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.port.in.IssuedUpload;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentUploadResponse(
    String uploadId,
    String state,
    String fileName,
    String contentType,
    long sizeBytes,
    String sha256,
    String rejectionReason,
    String uploadUrl,
    Map<String, String> uploadFields,
    String attachmentUrl,
    Instant expiresAt) {

  public AttachmentUploadResponse {
    uploadFields = uploadFields == null ? null : Map.copyOf(uploadFields);
  }

  static AttachmentUploadResponse issued(final IssuedUpload issued) {
    final AttachmentUpload upload = issued.upload();
    return new AttachmentUploadResponse(
        upload.uploadId().value(),
        upload.state().name(),
        upload.fileName(),
        upload.contentType(),
        upload.sizeBytes(),
        null,
        null,
        issued.uploadUrl(),
        issued.uploadFields(),
        issued.attachmentUrl(),
        issued.expiresAt());
  }

  static AttachmentUploadResponse of(final AttachmentUpload upload) {
    return new AttachmentUploadResponse(
        upload.uploadId().value(),
        upload.state().name(),
        upload.fileName(),
        upload.contentType(),
        upload.sizeBytes(),
        upload.sha256() == null ? null : upload.sha256().hex(),
        upload.rejectionReason() == null ? null : upload.rejectionReason().name(),
        null,
        null,
        null,
        null);
  }

  @Override
  public String toString() {
    return "AttachmentUploadResponse[uploadId=" + uploadId + ", state=" + state + "]";
  }
}
