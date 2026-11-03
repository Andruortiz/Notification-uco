package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateEngine;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.MissingTemplateVariablesException;
import co.edu.uco.notification.core.exception.TemplateArchivedException;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.exception.TemplateNotFoundException;
import co.edu.uco.notification.core.port.in.RenderTemplateUseCase;
import co.edu.uco.notification.core.port.in.RenderedTemplate;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    return Mono.defer(
        () -> {
          requireValueLimits(safeValues);
          return templateRepository
              .findById(tenantId, templateId)
              .switchIfEmpty(Mono.error(TemplateNotFoundException::new))
              .flatMap(
                  template -> {
                    if (template.archived()) {
                      return Mono.error(new TemplateArchivedException());
                    }
                    final ResolvedTemplate resolved =
                        resolveContent(
                            template.channel(),
                            template.subject(),
                            template.body(),
                            template.variables(),
                            safeValues);
                    final List<String> missing = missing(resolved, safeValues);
                    if (!missing.isEmpty()) {
                      return Mono.error(new MissingTemplateVariablesException(missing));
                    }
                    return Mono.just(new RenderedTemplate(resolved.subject(), resolved.body()));
                  });
        });
  }

  public ResolvedTemplate resolveContent(
      final ChannelType channelType,
      final String subject,
      final String body,
      final List<TemplateVariable> declared,
      final Map<String, String> values) {
    final Map<String, String> safeValues = values == null ? Map.of() : values;
    final List<TemplateVariable> declaredVariables = declared == null ? List.of() : declared;
    Preconditions.requireNonNull(body, "body must not be null");
    requireValueLimits(safeValues);
    Template.requireTextLimits(subject, body);
    final Set<String> detected = TemplateEngine.detect(subject, body);
    if (detected.size() > Template.MAX_VARIABLES) {
      throw new TemplateLimitExceededException(
          "more than " + Template.MAX_VARIABLES + " variables");
    }
    final Map<String, Boolean> required = new HashMap<>();
    for (final TemplateVariable variable : declaredVariables) {
      required.put(variable.name(), variable.required());
    }
    final List<TemplateVariable> variables =
        detected.stream()
            .map(name -> new TemplateVariable(name, required.getOrDefault(name, Boolean.TRUE)))
            .toList();
    return new ResolvedTemplate(
        subject == null ? null : TemplateEngine.render(subject, safeValues),
        TemplateEngine.render(body, safeValues),
        channelType,
        null,
        null,
        variables,
        List.of());
  }

  static List<String> missing(final ResolvedTemplate resolved, final Map<String, String> values) {
    return resolved.variables().stream()
        .filter(TemplateVariable::required)
        .map(TemplateVariable::name)
        .filter(name -> values.get(name) == null)
        .toList();
  }

  private static void requireValueLimits(final Map<String, String> values) {
    if (values.size() > MAX_VALUES
        || values.values().stream().anyMatch(v -> v != null && v.length() > MAX_VALUE_LENGTH)) {
      throw new TemplateLimitExceededException("values exceed the allowed limits");
    }
  }
}
