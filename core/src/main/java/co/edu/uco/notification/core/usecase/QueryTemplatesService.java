package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.TemplateNotFoundException;
import co.edu.uco.notification.core.port.in.QueryTemplatesUseCase;
import co.edu.uco.notification.core.port.in.TemplatePage;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.core.repository.TemplateSearchCriteria;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import reactor.core.publisher.Mono;

public final class QueryTemplatesService implements QueryTemplatesUseCase {

  private final TemplateRepository templateRepository;

  public QueryTemplatesService(final TemplateRepository templateRepository) {
    this.templateRepository =
        Preconditions.requireNonNull(templateRepository, "templateRepository must not be null");
  }

  @Override
  public Mono<TemplatePage> list(final TemplateSearchCriteria criteria) {
    Preconditions.requireNonNull(criteria, "criteria must not be null");
    return templateRepository
        .search(criteria)
        .collectList()
        .map(
            found -> {
              final boolean hasNext = found.size() > criteria.size();
              final List<Template> items = hasNext ? found.subList(0, criteria.size()) : found;
              return new TemplatePage(items, criteria.page(), criteria.size(), hasNext);
            });
  }

  @Override
  public Mono<Template> get(final TenantId tenantId, final TemplateId templateId) {
    return templateRepository
        .findById(tenantId, templateId)
        .switchIfEmpty(Mono.error(TemplateNotFoundException::new));
  }
}
