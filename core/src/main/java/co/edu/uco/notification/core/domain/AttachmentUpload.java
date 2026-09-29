package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ScanState;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.utils.Preconditions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

public record AttachmentUpload(
    UploadId uploadId,
    TenantId tenantId,
    String fileName,
    String contentType,
    long sizeBytes,
    String uploadKey,
    String cleanKey,
    ScanState state,
    AttachmentRejectionReason rejectionReason,
    String signature,
    Sha256Digest sha256,
    Instant issuedAt,
    Instant expiresAt,
    Instant completedAt,
    Instant scannedAt,
    Long version) {

  public AttachmentUpload {
    Preconditions.requireNonNull(uploadId, "uploadId must not be null");
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonBlank(uploadKey, "uploadKey must not be blank");
    Preconditions.requireNonNull(state, "state must not be null");
    Preconditions.requireNonNull(issuedAt, "issuedAt must not be null");
    Preconditions.requireNonNull(expiresAt, "expiresAt must not be null");
    contentType = AttachmentSubmission.normalizeContentType(contentType);
  }

  public static AttachmentUpload issue(
      final UploadId uploadId,
      final TenantId tenantId,
      final String fileName,
      final String contentType,
      final long sizeBytes,
      final Instant issuedAt,
      final Instant expiresAt) {
    return new AttachmentUpload(
        uploadId,
        tenantId,
        fileName,
        contentType,
        sizeBytes,
        uploadKeyFor(tenantId, uploadId),
        null,
        ScanState.PENDING_SCAN,
        null,
        null,
        null,
        issuedAt,
        expiresAt,
        null,
        null,
        null);
  }

  public static String uploadKeyFor(final TenantId tenantId, final UploadId uploadId) {
    return "tenants/" + keySegment(tenantId.value()) + "/uploads/" + keySegment(uploadId.value());
  }

  public static String cleanKeyFor(final TenantId tenantId, final UploadId uploadId) {
    return "tenants/" + keySegment(tenantId.value()) + "/clean/" + keySegment(uploadId.value());
  }

  public static Optional<UploadId> uploadIdFromKey(final TenantId tenantId, final String key) {
    final String prefix = "tenants/" + keySegment(tenantId.value()) + "/uploads/";
    if (key == null || !key.startsWith(prefix)) {
      return Optional.empty();
    }
    final String segment = key.substring(prefix.length());
    if (segment.isEmpty() || !segment.chars().allMatch(c -> isUnreserved((char) c))) {
      return Optional.empty();
    }
    return Optional.of(UploadId.of(segment));
  }

  public static String keySegment(final String value) {
    final StringBuilder segment = new StringBuilder();
    for (final byte octet : value.getBytes(StandardCharsets.UTF_8)) {
      final char character = (char) (octet & 0xFF);
      if (isUnreserved(character)) {
        segment.append(character);
      } else {
        segment.append('%').append(String.format("%02X", octet & 0xFF));
      }
    }
    return segment.toString();
  }

  private static boolean isUnreserved(final char character) {
    return (character >= 'a' && character <= 'z')
        || (character >= 'A' && character <= 'Z')
        || (character >= '0' && character <= '9')
        || character == '-'
        || character == '_'
        || character == '.';
  }

  public boolean isPendingScan() {
    return state == ScanState.PENDING_SCAN;
  }

  public AttachmentUpload markCompleted(final Instant now) {
    requirePendingScan();
    return new AttachmentUpload(
        uploadId,
        tenantId,
        fileName,
        contentType,
        sizeBytes,
        uploadKey,
        null,
        ScanState.PENDING_SCAN,
        null,
        null,
        sha256,
        issuedAt,
        expiresAt,
        now,
        scannedAt,
        version);
  }

  public AttachmentUpload markClean(final Sha256Digest digest, final Instant now) {
    requirePendingScan();
    Preconditions.requireNonNull(digest, "digest must not be null");
    return new AttachmentUpload(
        uploadId,
        tenantId,
        fileName,
        contentType,
        sizeBytes,
        uploadKey,
        cleanKeyFor(tenantId, uploadId),
        ScanState.CLEAN,
        null,
        null,
        digest,
        issuedAt,
        expiresAt,
        completedAt,
        now,
        version);
  }

  public AttachmentUpload markInfected(
      final Sha256Digest digest,
      final AttachmentRejectionReason reason,
      final String detectedSignature,
      final Instant now) {
    requirePendingScan();
    Preconditions.requireNonNull(digest, "digest must not be null");
    Preconditions.requireNonNull(reason, "reason must not be null");
    return new AttachmentUpload(
        uploadId,
        tenantId,
        fileName,
        contentType,
        sizeBytes,
        uploadKey,
        null,
        ScanState.INFECTED,
        reason,
        detectedSignature,
        digest,
        issuedAt,
        expiresAt,
        completedAt,
        now,
        version);
  }

  private void requirePendingScan() {
    if (state != ScanState.PENDING_SCAN) {
      throw new IllegalStateException(
          "upload " + uploadId.value() + " is " + state + " and cannot change state again");
    }
  }
}
