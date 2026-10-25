package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.repository.TemplateSearchCriteria;
import reactor.core.publisher.Mono;

public interface QueryTemplatesUseCase {

  Mono<TemplatePage> list(TemplateSearchCriteria criteria);

  Mono<Template> get(TenantId tenantId, TemplateId templateId);
}
