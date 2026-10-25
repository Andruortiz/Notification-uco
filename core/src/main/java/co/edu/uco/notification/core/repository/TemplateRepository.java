package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TemplateRepository {

  Mono<Template> insert(Template template);

  Mono<Template> findById(TenantId tenantId, TemplateId templateId);

  Mono<Template> replace(Template next, long expectedVersion);

  Flux<Template> search(TemplateSearchCriteria criteria);
}
