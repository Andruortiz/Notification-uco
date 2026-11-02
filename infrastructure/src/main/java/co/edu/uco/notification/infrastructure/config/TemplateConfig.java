package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.port.in.ArchiveTemplateUseCase;
import co.edu.uco.notification.core.port.in.CreateTemplateUseCase;
import co.edu.uco.notification.core.port.in.DetectTemplateVariablesUseCase;
import co.edu.uco.notification.core.port.in.PreviewTemplateUseCase;
import co.edu.uco.notification.core.port.in.QueryTemplatesUseCase;
import co.edu.uco.notification.core.port.in.UpdateTemplateUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.core.usecase.ArchiveTemplateService;
import co.edu.uco.notification.core.usecase.CreateTemplateService;
import co.edu.uco.notification.core.usecase.DetectTemplateVariablesService;
import co.edu.uco.notification.core.usecase.PreviewTemplateService;
import co.edu.uco.notification.core.usecase.QueryTemplatesService;
import co.edu.uco.notification.core.usecase.RenderTemplateService;
import co.edu.uco.notification.core.usecase.UpdateTemplateService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemplateConfig {

  @Bean
  CreateTemplateUseCase createTemplateUseCase(
      final TemplateRepository templateRepository,
      final ChannelCatalogPort channelCatalogPort,
      final Clock clock) {
    return new CreateTemplateService(templateRepository, channelCatalogPort, clock);
  }

  @Bean
  UpdateTemplateUseCase updateTemplateUseCase(
      final TemplateRepository templateRepository, final Clock clock) {
    return new UpdateTemplateService(templateRepository, clock);
  }

  @Bean
  ArchiveTemplateUseCase archiveTemplateUseCase(
      final TemplateRepository templateRepository, final Clock clock) {
    return new ArchiveTemplateService(templateRepository, clock);
  }

  @Bean
  QueryTemplatesUseCase queryTemplatesUseCase(final TemplateRepository templateRepository) {
    return new QueryTemplatesService(templateRepository);
  }

  @Bean
  DetectTemplateVariablesUseCase detectTemplateVariablesUseCase() {
    return new DetectTemplateVariablesService();
  }

  @Bean
  RenderTemplateService renderTemplateUseCase(final TemplateRepository templateRepository) {
    return new RenderTemplateService(templateRepository);
  }

  @Bean
  PreviewTemplateUseCase previewTemplateUseCase(final RenderTemplateService renderTemplateService) {
    return new PreviewTemplateService(renderTemplateService);
  }
}
