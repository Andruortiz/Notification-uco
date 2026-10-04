package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AttachmentUploadRepository {

  Mono<AttachmentUpload> insert(AttachmentUpload upload);

  Mono<AttachmentUpload> findByTenantAndId(TenantId tenantId, UploadId uploadId);

  Mono<Boolean> transition(AttachmentUpload expected, AttachmentUpload next);

  Flux<AttachmentUpload> findStalePending(
      Instant expiredBefore, Instant scanRequestedBefore, int limit);
}
