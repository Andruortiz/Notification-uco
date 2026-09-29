package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.utils.Preconditions;

public record AttachmentInspection(
    Sha256Digest sha256,
    AttachmentRejectionReason rejectionReason,
    String signature,
    String detectedContentType) {

  public AttachmentInspection {
    Preconditions.requireNonNull(sha256, "sha256 must not be null");
  }

  public static AttachmentInspection clean(final Sha256Digest sha256) {
    return new AttachmentInspection(sha256, null, null, null);
  }

  public static AttachmentInspection rejected(
      final Sha256Digest sha256,
      final AttachmentRejectionReason reason,
      final String signature,
      final String detectedContentType) {
    Preconditions.requireNonNull(reason, "reason must not be null");
    return new AttachmentInspection(sha256, reason, signature, detectedContentType);
  }

  public boolean isClean() {
    return rejectionReason == null;
  }
}
