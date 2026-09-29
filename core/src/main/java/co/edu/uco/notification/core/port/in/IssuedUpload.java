package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record IssuedUpload(AttachmentUpload upload, String uploadUrl, Instant expiresAt) {

  public IssuedUpload {
    Preconditions.requireNonNull(upload, "upload must not be null");
    Preconditions.requireNonBlank(uploadUrl, "uploadUrl must not be blank");
    Preconditions.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  @Override
  public String toString() {
    return "IssuedUpload[uploadId=" + upload.uploadId().value() + ", expiresAt=" + expiresAt + "]";
  }
}
