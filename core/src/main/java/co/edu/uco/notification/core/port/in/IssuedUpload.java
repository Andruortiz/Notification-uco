package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.Map;

public record IssuedUpload(
    AttachmentUpload upload,
    String uploadUrl,
    Map<String, String> uploadFields,
    String attachmentUrl,
    Instant expiresAt) {

  public IssuedUpload {
    Preconditions.requireNonNull(upload, "upload must not be null");
    Preconditions.requireNonBlank(uploadUrl, "uploadUrl must not be blank");
    Preconditions.requireNonNull(uploadFields, "uploadFields must not be null");
    Preconditions.requireNonBlank(attachmentUrl, "attachmentUrl must not be blank");
    Preconditions.requireNonNull(expiresAt, "expiresAt must not be null");
    uploadFields = Map.copyOf(uploadFields);
  }

  @Override
  public String toString() {
    return "IssuedUpload[uploadId=" + upload.uploadId().value() + ", expiresAt=" + expiresAt + "]";
  }
}
