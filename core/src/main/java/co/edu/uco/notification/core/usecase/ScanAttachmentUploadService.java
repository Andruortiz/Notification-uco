package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
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
                storage.stat(upload.uploadKey()).flatMap(info -> scanStoredObject(upload, info)));
  }

  private Mono<AttachmentUpload> scanStoredObject(
      final AttachmentUpload upload, final StoredObjectInfo info) {
    return storage
        .read(upload.uploadKey(), info.etag())
        .flatMap(
            content ->
                content.length == upload.sizeBytes()
                    ? inspectAndResolve(upload, info, content)
                    : storage.delete(upload.uploadKey()).then(Mono.<AttachmentUpload>empty()));
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
        .filter(Boolean::booleanValue)
        .flatMap(won -> storage.delete(upload.uploadKey()).thenReturn(clean));
  }

  private Mono<AttachmentUpload> markInfected(
      final AttachmentUpload upload, final AttachmentInspection inspection) {
    final AttachmentUpload infected =
        upload.markInfected(
            inspection.sha256(),
            inspection.rejectionReason(),
            inspection.signature(),
            clock.instant());
    return storage
        .delete(upload.uploadKey())
        .then(Mono.defer(() -> repository.transition(upload, infected)))
        .filter(Boolean::booleanValue)
        .map(won -> infected);
  }
}
