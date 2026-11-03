package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.port.in.CreateTemplateCommand;
import co.edu.uco.notification.core.port.in.CreateTemplateUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.util.List;
import reactor.core.publisher.Mono;

public final class CreateTemplateService implements CreateTemplateUseCase {

  private final TemplateRepository templateRepository;
  private final ChannelCatalogPort channelCatalogPort;
  private final Clock clock;

  public CreateTemplateService(
      final TemplateRepository templateRepository,
      final ChannelCatalogPort channelCatalogPort,
      final Clock clock) {
    this.templateRepository =
        Preconditions.requireNonNull(templateRepository, "templateRepository must not be null");
    this.channelCatalogPort =
        Preconditions.requireNonNull(channelCatalogPort, "channelCatalogPort must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<Template> create(final CreateTemplateCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");
    return Mono.defer(
        () -> {
          final ChannelType channel = ChannelType.of(command.channel().value().toUpperCase());
          return channelCatalogPort
              .findActiveRoute(channel, command.tenantId())
              .switchIfEmpty(Mono.error(() -> new ChannelNotAvailableException(channel)))
              .then(
                  Mono.fromSupplier(
                      () ->
                          Template.create(
                              command.tenantId(),
                              command.key(),
                              command.category(),
                              channel,
                              command.language(),
                              command.subject(),
                              command.body(),
                              command.variables() == null ? List.of() : command.variables(),
                              command.sampleValues(),
                              clock.instant())))
              .flatMap(templateRepository::insert);
        });
  }
}
