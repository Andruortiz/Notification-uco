package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.policy.AttachmentPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ScanVerdict;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.port.out.ContentTypeDetectorPort;
import co.edu.uco.notification.core.port.out.MalwareScannerPort;
import co.edu.uco.notification.core.port.out.ScanVerdictCachePort;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class AttachmentInspector {

  private static final String CSV = "text/csv";
  private static final String PLAIN_TEXT = "text/plain";

  private final ContentTypeDetectorPort contentTypeDetector;
  private final MalwareScannerPort malwareScanner;
  private final ScanVerdictCachePort scanVerdictCache;

  public AttachmentInspector(
      final ContentTypeDetectorPort contentTypeDetector,
      final MalwareScannerPort malwareScanner,
      final ScanVerdictCachePort scanVerdictCache) {
    this.contentTypeDetector =
        Preconditions.requireNonNull(contentTypeDetector, "contentTypeDetector must not be null");
    this.malwareScanner =
        Preconditions.requireNonNull(malwareScanner, "malwareScanner must not be null");
    this.scanVerdictCache =
        Preconditions.requireNonNull(scanVerdictCache, "scanVerdictCache must not be null");
  }

  public Mono<AttachmentInspection> inspect(
      final TenantId tenantId,
      final String fileName,
      final String declaredContentType,
      final byte[] content) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(content, "content must not be null");
    final Sha256Digest sha256 = Sha256Digest.of(content);
    return unavailableOnError(contentTypeDetector.detect(content, fileName))
        .flatMap(
            detected ->
                matches(declaredContentType, detected)
                    ? verdictFor(tenantId, sha256, content)
                        .map(verdict -> toInspection(sha256, verdict))
                    : Mono.just(
                        AttachmentInspection.rejected(
                            sha256,
                            AttachmentRejectionReason.CONTENT_TYPE_MISMATCH,
                            null,
                            detected)));
  }

  private static boolean matches(final String declaredContentType, final String detected) {
    final String declared = AttachmentSubmission.normalizeContentType(declaredContentType);
    final String actual = AttachmentSubmission.normalizeContentType(detected);
    if (declared == null || !AttachmentPolicy.ALLOWED_CONTENT_TYPES.contains(declared)) {
      return false;
    }
    return declared.equals(actual) || CSV.equals(declared) && PLAIN_TEXT.equals(actual);
  }

  private Mono<ScanVerdict> verdictFor(
      final TenantId tenantId, final Sha256Digest sha256, final byte[] content) {
    return unavailableOnError(scanVerdictCache.find(tenantId, sha256))
        .flatMap(cached -> reuseOrRescan(tenantId, sha256, content, cached))
        .switchIfEmpty(Mono.defer(() -> scanAndRemember(tenantId, sha256, content)));
  }

  private Mono<ScanVerdict> reuseOrRescan(
      final TenantId tenantId,
      final Sha256Digest sha256,
      final byte[] content,
      final ScanVerdict cached) {
    if (cached.isInfected()) {
      return Mono.just(cached);
    }
    return unavailableOnError(malwareScanner.signatureVersion())
        .flatMap(
            currentVersion ->
                currentVersion.equals(cached.signatureVersion())
                    ? Mono.just(cached)
                    : scanAndRemember(tenantId, sha256, content));
  }

  private Mono<ScanVerdict> scanAndRemember(
      final TenantId tenantId, final Sha256Digest sha256, final byte[] content) {
    return unavailableOnError(malwareScanner.scan(content))
        .flatMap(
            verdict ->
                unavailableOnError(scanVerdictCache.save(tenantId, sha256, verdict))
                    .thenReturn(verdict));
  }

  private static AttachmentInspection toInspection(
      final Sha256Digest sha256, final ScanVerdict verdict) {
    return verdict.isInfected()
        ? AttachmentInspection.rejected(
            sha256, AttachmentRejectionReason.MALWARE, verdict.signature(), null)
        : AttachmentInspection.clean(sha256);
  }

  private static <T> Mono<T> unavailableOnError(final Mono<T> source) {
    return source.onErrorMap(
        error -> !(error instanceof AttachmentInspectionUnavailableException),
        error ->
            new AttachmentInspectionUnavailableException(
                "attachment inspection is not available right now", error));
  }
}
