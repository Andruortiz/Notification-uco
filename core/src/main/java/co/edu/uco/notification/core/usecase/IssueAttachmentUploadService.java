package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.policy.AttachmentPolicy;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.IssueAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.in.IssuedUpload;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import reactor.core.publisher.Mono;

public final class IssueAttachmentUploadService implements IssueAttachmentUploadUseCase {

  private final AttachmentUploadRepository repository;
  private final AttachmentStoragePort storage;
  private final Duration expiration;
  private final Clock clock;

  public IssueAttachmentUploadService(
      final AttachmentUploadRepository repository,
      final AttachmentStoragePort storage,
      final Duration expiration,
      final Clock clock) {
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
    this.storage = Preconditions.requireNonNull(storage, "storage must not be null");
    this.expiration = Preconditions.requireNonNull(expiration, "expiration must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<IssuedUpload> issue(
      final TenantId tenantId,
      final String fileName,
      final String contentType,
      final Long sizeBytes) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    AttachmentPolicy.validateUploadRequest(fileName, contentType, sizeBytes);
    final Instant now = clock.instant();
    final AttachmentUpload upload =
        AttachmentUpload.issue(
            UploadId.newId(),
            tenantId,
            fileName,
            contentType,
            sizeBytes,
            now,
            now.plus(expiration));
    return storage
        .presignUpload(upload.uploadKey(), expiration)
        .flatMap(
            presigned ->
                repository
                    .insert(upload)
                    .map(saved -> new IssuedUpload(saved, presigned.url(), presigned.expiresAt())));
  }
}
