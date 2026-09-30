package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record Attachment(
    TenantId tenantId,
    String fileName,
    String contentType,
    long sizeBytes,
    Sha256Digest sha256,
    AttachmentSource source) {

  public Attachment {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(sha256, "sha256 must not be null");
    Preconditions.requireNonNull(source, "source must not be null");
    contentType = AttachmentSubmission.normalizeContentType(contentType);
  }

  @Override
  public String toString() {
    return "Attachment[fileName="
        + fileName
        + ", contentType="
        + contentType
        + ", sizeBytes="
        + sizeBytes
        + ", sha256="
        + sha256.hex()
        + "]";
  }
}
