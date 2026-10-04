package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.policy.AttachmentPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.ScanState;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.util.Optional;
import reactor.core.publisher.Mono;

public final class ScanAttachmentUploadService implements ScanAttachmentUploadUseCase {

  private final AttachmentUploadRepository repository;
  private final AttachmentStoragePort storage;
  private final AttachmentInspector inspector;
  private final Clock clock;

  public ScanAttachmentUploadService(
      final AttachmentUploadRepository repository,
      final AttachmentStoragePort storage,
      final AttachmentInspector inspector,
      final Clock clock) {
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
    this.storage = Preconditions.requireNonNull(storage, "storage must not be null");
    this.inspector = Preconditions.requireNonNull(inspector, "inspector must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<AttachmentUpload> scan(final TenantId tenantId, final UploadId uploadId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(uploadId, "uploadId must not be null");
    return repository
        .findByTenantAndId(tenantId, uploadId)
        .filter(AttachmentUpload::isPendingScan)
        .flatMap(
            upload ->
                storage
                    .stat(upload.uploadKey())
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(info -> scanIfPresent(upload, info)));
  }

  private Mono<AttachmentUpload> scanIfPresent(
      final AttachmentUpload upload, final Optional<StoredObjectInfo> info) {
    if (info.isEmpty()) {
      return fail(upload, AttachmentRejectionReason.OBJECT_MISSING);
    }
    final long size = info.get().sizeBytes();
    if (size != upload.sizeBytes() || size > AttachmentPolicy.MAX_SIZE_BYTES) {
      return fail(upload, AttachmentRejectionReason.SIZE_MISMATCH);
    }
    return storage
        .read(upload.uploadKey(), info.get().etag())
        .flatMap(content -> inspectAndResolve(upload, info.get(), content));
  }

  private Mono<AttachmentUpload> fail(
      final AttachmentUpload upload, final AttachmentRejectionReason reason) {
    final AttachmentUpload failed = upload.markFailed(reason, clock.instant());
    return repository
        .transition(upload, failed)
        .filter(Boolean::booleanValue)
        .flatMap(
            won ->
                StorageCleanup.bestEffort(storage.delete(upload.uploadKey())).thenReturn(failed));
  }

  private Mono<AttachmentUpload> inspectAndResolve(
      final AttachmentUpload upload, final StoredObjectInfo info, final byte[] content) {
    return inspector
        .inspect(upload.tenantId(), upload.fileName(), upload.contentType(), content)
        .flatMap(
            inspection ->
                inspection.isClean()
                    ? markClean(upload, info, inspection)
                    : markInfected(upload, inspection));
  }

  private Mono<AttachmentUpload> markClean(
      final AttachmentUpload upload,
      final StoredObjectInfo info,
      final AttachmentInspection inspection) {
    final AttachmentUpload clean = upload.markClean(inspection.sha256(), clock.instant());
    return storage
        .copyIfMatch(upload.uploadKey(), clean.cleanKey(), info.etag())
        .then(Mono.defer(() -> repository.transition(upload, clean)))
        .flatMap(
            won ->
                won
                    ? StorageCleanup.bestEffort(storage.delete(upload.uploadKey()))
                        .thenReturn(clean)
                    : discardOrphanCopy(upload, clean));
  }

  private Mono<AttachmentUpload> discardOrphanCopy(
      final AttachmentUpload upload, final AttachmentUpload clean) {
    return repository
        .findByTenantAndId(upload.tenantId(), upload.uploadId())
        .filter(current -> current.state() != ScanState.CLEAN)
        .flatMap(current -> storage.delete(clean.cleanKey()))
        .then(Mono.empty());
  }

  private Mono<AttachmentUpload> markInfected(
      final AttachmentUpload upload, final AttachmentInspection inspection) {
    final AttachmentUpload infected =
        upload.markInfected(
            inspection.sha256(),
            inspection.rejectionReason(),
            inspection.signature(),
            clock.instant());
    return repository
        .transition(upload, infected)
        .filter(Boolean::booleanValue)
        .flatMap(
            won ->
                StorageCleanup.bestEffort(storage.delete(upload.uploadKey())).thenReturn(infected));
  }
}
