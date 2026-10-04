package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.ScanState;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AttachmentUploadTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final UploadId UPLOAD_ID = UploadId.of("upload-1");
  private static final Instant ISSUED = Instant.parse("2026-09-29T10:00:00Z");
  private static final Instant LATER = ISSUED.plusSeconds(60);
  private static final Sha256Digest SHA = Sha256Digest.of("x".getBytes(StandardCharsets.UTF_8));

  private static AttachmentUpload issued() {
    return AttachmentUpload.issue(
        UPLOAD_ID,
        TENANT,
        "contract.pdf",
        "Application/PDF",
        2_000_000L,
        ISSUED,
        ISSUED.plusSeconds(900));
  }

  @Test
  void issueStartsPendingScanWithAnUploadKeyScopedByTenant() {
    final AttachmentUpload upload = issued();

    assertEquals(ScanState.PENDING_SCAN, upload.state());
    assertEquals("uploads/tenant-1/upload-1", upload.uploadKey());
    assertNull(upload.cleanKey());
    assertNull(upload.sha256());
    assertNull(upload.completedAt());
    assertEquals("application/pdf", upload.contentType());
    assertEquals(ISSUED.plusSeconds(900), upload.expiresAt());
  }

  @Test
  void markCompletedKeepsPendingScanAndRecordsTheTime() {
    final AttachmentUpload completed = issued().markCompleted(LATER);

    assertEquals(ScanState.PENDING_SCAN, completed.state());
    assertEquals(LATER, completed.completedAt());
  }

  @Test
  void markCleanSetsTheCleanKeyAndHash() {
    final AttachmentUpload clean = issued().markCompleted(LATER).markClean(SHA, LATER);

    assertEquals(ScanState.CLEAN, clean.state());
    assertEquals("tenants/tenant-1/clean/upload-1", clean.cleanKey());
    assertEquals(SHA, clean.sha256());
    assertEquals(LATER, clean.scannedAt());
    assertNull(clean.rejectionReason());
  }

  @Test
  void markInfectedRecordsReasonAndSignatureWithoutACleanKey() {
    final AttachmentUpload infected =
        issued()
            .markInfected(SHA, AttachmentRejectionReason.MALWARE, "Eicar-Test-Signature", LATER);

    assertEquals(ScanState.INFECTED, infected.state());
    assertEquals(AttachmentRejectionReason.MALWARE, infected.rejectionReason());
    assertEquals("Eicar-Test-Signature", infected.signature());
    assertEquals(SHA, infected.sha256());
    assertNull(infected.cleanKey());
  }

  @ParameterizedTest
  @EnumSource(
      value = AttachmentRejectionReason.class,
      names = {"OBJECT_MISSING", "SIZE_MISMATCH", "SCAN_EXHAUSTED", "EXPIRED"})
  void markFailedIsTerminalAndRecordsItsReason(final AttachmentRejectionReason reason) {
    final AttachmentUpload failed = issued().markFailed(reason, LATER);

    assertEquals(ScanState.FAILED, failed.state());
    assertEquals(reason, failed.rejectionReason());
    assertEquals(LATER, failed.scannedAt());
    assertNull(failed.cleanKey());
    assertThrows(IllegalStateException.class, () -> failed.markCompleted(LATER));
    assertThrows(IllegalStateException.class, () -> failed.markClean(SHA, LATER));
    assertThrows(IllegalStateException.class, () -> failed.markFailed(reason, LATER));
  }

  @Test
  void markFailedRejectsAReasonThatBelongsToTheScan() {
    assertThrows(
        IllegalArgumentException.class,
        () -> issued().markFailed(AttachmentRejectionReason.MALWARE, LATER));
    assertThrows(NullPointerException.class, () -> issued().markFailed(null, LATER));
  }

  @Test
  void aCleanUploadCannotFail() {
    final AttachmentUpload clean = issued().markClean(SHA, LATER);

    assertThrows(
        IllegalStateException.class,
        () -> clean.markFailed(AttachmentRejectionReason.EXPIRED, LATER));
  }

  @Test
  void expiryIsMeasuredAgainstTheGivenInstant() {
    final AttachmentUpload upload = issued();

    assertEquals(false, upload.isExpiredAt(ISSUED.plusSeconds(900)));
    assertEquals(true, upload.isExpiredAt(ISSUED.plusSeconds(901)));
  }

  @Test
  void terminalStatesDoNotTransitionAgain() {
    final AttachmentUpload clean = issued().markClean(SHA, LATER);
    final AttachmentUpload infected =
        issued().markInfected(SHA, AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, null, LATER);

    assertThrows(IllegalStateException.class, () -> clean.markCompleted(LATER));
    assertThrows(IllegalStateException.class, () -> clean.markClean(SHA, LATER));
    assertThrows(
        IllegalStateException.class,
        () -> clean.markInfected(SHA, AttachmentRejectionReason.MALWARE, "sig", LATER));
    assertThrows(IllegalStateException.class, () -> infected.markClean(SHA, LATER));
    assertThrows(IllegalStateException.class, () -> infected.markCompleted(LATER));
  }

  @Test
  void keysAreDerivedFromTenantAndUpload() {
    assertEquals(
        "uploads/tenant-2/abc",
        AttachmentUpload.uploadKeyFor(TenantId.of("tenant-2"), UploadId.of("abc")));
    assertEquals(
        "tenants/tenant-2/clean/abc",
        AttachmentUpload.cleanKeyFor(TenantId.of("tenant-2"), UploadId.of("abc")));
  }

  @Test
  void uploadIdIsReadBackOnlyFromAKeyOfTheSameTenant() {
    final String key = AttachmentUpload.uploadKeyFor(TENANT, UPLOAD_ID);

    assertEquals(Optional.of(UPLOAD_ID), AttachmentUpload.uploadIdFromKey(TENANT, key));
    assertEquals(Optional.empty(), AttachmentUpload.uploadIdFromKey(TenantId.of("tenant-2"), key));
    assertEquals(
        Optional.empty(),
        AttachmentUpload.uploadIdFromKey(TENANT, "tenants/tenant-1/clean/upload-1"));
    assertEquals(Optional.empty(), AttachmentUpload.uploadIdFromKey(TENANT, "uploads/tenant-1/"));
    assertEquals(
        Optional.empty(),
        AttachmentUpload.uploadIdFromKey(TENANT, "uploads/tenant-1/a/../../tenant-2/x"));
    assertEquals(Optional.empty(), AttachmentUpload.uploadIdFromKey(TENANT, "uploads/tenant-1/."));
    assertEquals(Optional.empty(), AttachmentUpload.uploadIdFromKey(TENANT, "uploads/tenant-1/.."));
    assertEquals(
        Optional.of(UploadId.of("a.b")),
        AttachmentUpload.uploadIdFromKey(TENANT, "uploads/tenant-1/a.b"));
    assertEquals(Optional.empty(), AttachmentUpload.uploadIdFromKey(TENANT, null));
  }

  @Test
  void keySegmentsCannotEscapeTheirTenantPrefix() {
    assertEquals(
        "uploads/a%2F..%2Fb%20c/x%2Fy",
        AttachmentUpload.uploadKeyFor(TenantId.of("a/../b c"), UploadId.of("x/y")));
  }
}
