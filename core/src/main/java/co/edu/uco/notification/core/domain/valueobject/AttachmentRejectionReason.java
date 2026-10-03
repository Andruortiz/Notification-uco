package co.edu.uco.notification.core.domain.valueobject;

public enum AttachmentRejectionReason {
  MALWARE,
  CONTENT_TYPE_MISMATCH,
  OBJECT_MISSING,
  SIZE_MISMATCH,
  SCAN_EXHAUSTED,
  EXPIRED;

  public boolean isFailure() {
    return this == OBJECT_MISSING
        || this == SIZE_MISMATCH
        || this == SCAN_EXHAUSTED
        || this == EXPIRED;
  }
}
