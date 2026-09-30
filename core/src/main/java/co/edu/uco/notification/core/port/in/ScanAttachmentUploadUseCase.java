package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import reactor.core.publisher.Mono;

public interface ScanAttachmentUploadUseCase {

  Mono<AttachmentUpload> scan(TenantId tenantId, UploadId uploadId);
}
