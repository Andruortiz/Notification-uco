package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.port.in.GetAttachmentUploadUseCase;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class GetAttachmentUploadService implements GetAttachmentUploadUseCase {

  private final AttachmentUploadRepository repository;

  public GetAttachmentUploadService(final AttachmentUploadRepository repository) {
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
  }

  @Override
  public Mono<AttachmentUpload> get(final TenantId tenantId, final UploadId uploadId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(uploadId, "uploadId must not be null");
    return repository
        .findByTenantAndId(tenantId, uploadId)
        .switchIfEmpty(Mono.error(AttachmentUploadNotFoundException::new));
  }
}
