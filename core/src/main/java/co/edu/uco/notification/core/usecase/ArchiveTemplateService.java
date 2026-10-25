package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.TemplateNotFoundException;
import co.edu.uco.notification.core.exception.TemplateVersionConflictException;
import co.edu.uco.notification.core.port.in.ArchiveTemplateUseCase;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import reactor.core.publisher.Mono;

public final class ArchiveTemplateService implements ArchiveTemplateUseCase {

  private final TemplateRepository templateRepository;
  private final Clock clock;

  public ArchiveTemplateService(final TemplateRepository templateRepository, final Clock clock) {
    this.templateRepository =
        Preconditions.requireNonNull(templateRepository, "templateRepository must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<Template> archive(final TenantId tenantId, final TemplateId templateId) {
    return templateRepository
        .findById(tenantId, templateId)
        .switchIfEmpty(Mono.error(TemplateNotFoundException::new))
        .flatMap(
            current ->
                current.archived()
                    ? Mono.just(current)
                    : templateRepository
                        .replace(current.archive(clock.instant()), current.version())
                        .switchIfEmpty(Mono.error(TemplateVersionConflictException::new)));
  }
}
