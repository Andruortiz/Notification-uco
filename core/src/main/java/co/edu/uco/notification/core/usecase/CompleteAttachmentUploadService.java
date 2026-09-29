package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.port.in.CompleteAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.out.AttachmentScanRequestPort;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import reactor.core.publisher.Mono;

public final class CompleteAttachmentUploadService implements CompleteAttachmentUploadUseCase {

  private final AttachmentUploadRepository repository;
  private final AttachmentStoragePort storage;
  private final AttachmentScanRequestPort scanRequests;
  private final Clock clock;

  public CompleteAttachmentUploadService(
      final AttachmentUploadRepository repository,
      final AttachmentStoragePort storage,
      final AttachmentScanRequestPort scanRequests,
      final Clock clock) {
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
    this.storage = Preconditions.requireNonNull(storage, "storage must not be null");
    this.scanRequests = Preconditions.requireNonNull(scanRequests, "scanRequests must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<AttachmentUpload> complete(final TenantId tenantId, final UploadId uploadId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(uploadId, "uploadId must not be null");
    return repository
        .findByTenantAndId(tenantId, uploadId)
        .switchIfEmpty(Mono.error(AttachmentUploadNotFoundException::new))
        .flatMap(
            upload -> upload.isPendingScan() ? verifyAndRequestScan(upload) : Mono.just(upload));
  }

  private Mono<AttachmentUpload> verifyAndRequestScan(final AttachmentUpload upload) {
    return storage
        .stat(upload.uploadKey())
        .switchIfEmpty(Mono.error(AttachmentNotUploadedException::new))
        .flatMap(info -> requestScanIfComplete(upload, info));
  }

  private Mono<AttachmentUpload> requestScanIfComplete(
      final AttachmentUpload upload, final StoredObjectInfo info) {
    if (info.sizeBytes() != upload.sizeBytes()) {
      return storage
          .delete(upload.uploadKey())
          .then(
              Mono.error(
                  InvalidAttachmentException.forUpload(
                      "the uploaded file size does not match sizeBytes", upload.fileName())));
    }
    final AttachmentUpload completed = upload.markCompleted(clock.instant());
    return repository
        .transition(upload, completed)
        .then(Mono.defer(() -> scanRequests.requestScan(upload.tenantId(), upload.uploadId())))
        .thenReturn(completed);
  }
}
