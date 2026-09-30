package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import reactor.core.publisher.Mono;

public interface AttachmentScanRequestPort {

  Mono<Void> requestScan(TenantId tenantId, UploadId uploadId);
}
