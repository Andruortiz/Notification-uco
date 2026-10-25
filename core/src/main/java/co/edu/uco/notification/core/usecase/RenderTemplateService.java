package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateEngine;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.MissingTemplateVariablesException;
import co.edu.uco.notification.core.exception.TemplateArchivedException;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.exception.TemplateNotFoundException;
import co.edu.uco.notification.core.port.in.RenderTemplateUseCase;
import co.edu.uco.notification.core.port.in.RenderedTemplate;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public final class RenderTemplateService implements RenderTemplateUseCase {

  public static final int MAX_VALUES = 50;
  public static final int MAX_VALUE_LENGTH = 2000;

  private final TemplateRepository templateRepository;

  public RenderTemplateService(final TemplateRepository templateRepository) {
    this.templateRepository =
        Preconditions.requireNonNull(templateRepository, "templateRepository must not be null");
  }

  @Override
  public Mono<RenderedTemplate> render(
      final TenantId tenantId, final TemplateId templateId, final Map<String, String> values) {
    final Map<String, String> safeValues = values == null ? Map.of() : values;
    if (safeValues.size() > MAX_VALUES
        || safeValues.values().stream().anyMatch(v -> v != null && v.length() > MAX_VALUE_LENGTH)) {
      return Mono.error(new TemplateLimitExceededException("values exceed the allowed limits"));
    }
    return templateRepository
        .findById(tenantId, templateId)
        .switchIfEmpty(Mono.error(TemplateNotFoundException::new))
        .flatMap(
            template -> {
              if (template.archived()) {
                return Mono.error(new TemplateArchivedException());
              }
              final List<String> missing = missing(template, safeValues);
              if (!missing.isEmpty()) {
                return Mono.error(new MissingTemplateVariablesException(missing));
              }
              return Mono.just(
                  new RenderedTemplate(
                      template.subject() == null
                          ? null
                          : TemplateEngine.render(template.subject(), safeValues),
                      TemplateEngine.render(template.body(), safeValues)));
            });
  }

  private static List<String> missing(final Template template, final Map<String, String> values) {
    return template.variables().stream()
        .filter(TemplateVariable::required)
        .map(TemplateVariable::name)
        .filter(name -> values.get(name) == null)
        .toList();
  }
}
