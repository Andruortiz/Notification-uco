package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.exception.TemplateArchivedException;
import co.edu.uco.notification.core.exception.TemplateNotFoundException;
import co.edu.uco.notification.core.exception.TemplateVersionConflictException;
import co.edu.uco.notification.core.port.in.UpdateTemplateCommand;
import co.edu.uco.notification.core.port.in.UpdateTemplateUseCase;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.util.List;
import reactor.core.publisher.Mono;

public final class UpdateTemplateService implements UpdateTemplateUseCase {

  private final TemplateRepository templateRepository;
  private final Clock clock;

  public UpdateTemplateService(final TemplateRepository templateRepository, final Clock clock) {
    this.templateRepository =
        Preconditions.requireNonNull(templateRepository, "templateRepository must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<Template> update(final UpdateTemplateCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");
    return templateRepository
        .findById(command.tenantId(), command.templateId())
        .switchIfEmpty(Mono.error(TemplateNotFoundException::new))
        .flatMap(
            current -> {
              if (current.archived()) {
                return Mono.error(new TemplateArchivedException());
              }
              if (current.version() != command.version()) {
                return Mono.error(new TemplateVersionConflictException());
              }
              final Template next =
                  current.update(
                      command.category(),
                      command.subject(),
                      command.body(),
                      command.variables() == null ? List.of() : command.variables(),
                      command.sampleValues(),
                      clock.instant());
              return templateRepository
                  .replace(next, current.version())
                  .switchIfEmpty(Mono.error(TemplateVersionConflictException::new));
            });
  }
}
