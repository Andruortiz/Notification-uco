package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Mono;

public interface IssueAttachmentUploadUseCase {

  Mono<IssuedUpload> issue(TenantId tenantId, String fileName, String contentType, Long sizeBytes);
}
