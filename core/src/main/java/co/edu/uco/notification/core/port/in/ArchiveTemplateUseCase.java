package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Mono;

public interface ArchiveTemplateUseCase {

  Mono<Template> archive(TenantId tenantId, TemplateId templateId);
}
