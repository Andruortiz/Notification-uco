package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentUploadExpiredException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.port.in.CompleteAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.out.AttachmentScanRequestPort;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Instant;
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
        .flatMap(this::completePending);
  }

  private Mono<AttachmentUpload> completePending(final AttachmentUpload upload) {
    if (!upload.isPendingScan()) {
      return Mono.just(upload);
    }
    if (upload.completedAt() != null) {
      return Mono.just(upload);
    }
    final Instant now = clock.instant();
    if (upload.isExpiredAt(now)) {
      return expire(upload, now);
    }
    return verifyAndRequestScan(upload, now);
  }

  private Mono<AttachmentUpload> expire(final AttachmentUpload upload, final Instant now) {
    final AttachmentUpload failed = upload.markFailed(AttachmentRejectionReason.EXPIRED, now);
    return repository
        .transition(upload, failed)
        .filter(Boolean::booleanValue)
        .flatMap(won -> StorageCleanup.bestEffort(storage.delete(upload.uploadKey())))
        .then(Mono.error(new AttachmentUploadExpiredException()));
  }

  private Mono<AttachmentUpload> verifyAndRequestScan(
      final AttachmentUpload upload, final Instant now) {
    return storage
        .stat(upload.uploadKey())
        .switchIfEmpty(Mono.error(AttachmentNotUploadedException::new))
        .flatMap(info -> requestScanIfComplete(upload, info, now));
  }

  private Mono<AttachmentUpload> requestScanIfComplete(
      final AttachmentUpload upload, final StoredObjectInfo info, final Instant now) {
    if (info.sizeBytes() != upload.sizeBytes()) {
      return storage
          .delete(upload.uploadKey())
          .then(
              Mono.error(
                  InvalidAttachmentException.forUpload(
                      "the uploaded file size does not match sizeBytes", upload.fileName())));
    }
    return repository
        .transition(upload, upload.markCompleted(now))
        .flatMap(
            won ->
                won
                    ? scanRequests.requestScan(upload.tenantId(), upload.uploadId())
                    : Mono.<Void>empty())
        .then(Mono.defer(() -> current(upload)));
  }

  private Mono<AttachmentUpload> current(final AttachmentUpload upload) {
    return repository
        .findByTenantAndId(upload.tenantId(), upload.uploadId())
        .switchIfEmpty(Mono.error(AttachmentUploadNotFoundException::new));
  }
}
