package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import reactor.core.publisher.Mono;

public interface CompleteAttachmentUploadUseCase {

  Mono<AttachmentUpload> complete(TenantId tenantId, UploadId uploadId);
}
