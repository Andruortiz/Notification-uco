package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateStatus;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.port.in.ArchiveTemplateUseCase;
import co.edu.uco.notification.core.port.in.CreateTemplateCommand;
import co.edu.uco.notification.core.port.in.CreateTemplateUseCase;
import co.edu.uco.notification.core.port.in.DetectTemplateVariablesUseCase;
import co.edu.uco.notification.core.port.in.QueryTemplatesUseCase;
import co.edu.uco.notification.core.port.in.RenderTemplateUseCase;
import co.edu.uco.notification.core.port.in.UpdateTemplateCommand;
import co.edu.uco.notification.core.port.in.UpdateTemplateUseCase;
import co.edu.uco.notification.core.repository.TemplateSearchCriteria;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.ArchiveTemplateRequest;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.CreateTemplateRequest;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.DetectTemplateVariablesRequest;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.DetectedTemplateVariablesResponse;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.RenderTemplateRequest;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.RenderedTemplateResponse;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.TemplateListResponse;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.TemplateResponse;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.UpdateTemplateRequest;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.VariableDto;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@RestController
public class TemplateController {

  private static final Logger LOGGER = LoggerFactory.getLogger(TemplateController.class);

  private final CreateTemplateUseCase createUseCase;
  private final UpdateTemplateUseCase updateUseCase;
  private final ArchiveTemplateUseCase archiveUseCase;
  private final QueryTemplatesUseCase queryUseCase;
  private final DetectTemplateVariablesUseCase detectUseCase;
  private final RenderTemplateUseCase renderUseCase;

  public TemplateController(
      final CreateTemplateUseCase createUseCase,
      final UpdateTemplateUseCase updateUseCase,
      final ArchiveTemplateUseCase archiveUseCase,
      final QueryTemplatesUseCase queryUseCase,
      final DetectTemplateVariablesUseCase detectUseCase,
      final RenderTemplateUseCase renderUseCase) {
    this.createUseCase = Preconditions.requireNonNull(createUseCase, "createUseCase");
    this.updateUseCase = Preconditions.requireNonNull(updateUseCase, "updateUseCase");
    this.archiveUseCase = Preconditions.requireNonNull(archiveUseCase, "archiveUseCase");
    this.queryUseCase = Preconditions.requireNonNull(queryUseCase, "queryUseCase");
    this.detectUseCase = Preconditions.requireNonNull(detectUseCase, "detectUseCase");
    this.renderUseCase = Preconditions.requireNonNull(renderUseCase, "renderUseCase");
  }

  @PostMapping("/templates")
  public Mono<ResponseEntity<TemplateResponse>> create(
      final AuthenticatedPrincipal principal, @RequestBody final CreateTemplateRequest request) {
    return Mono.defer(
            () ->
                createUseCase.create(
                    new CreateTemplateCommand(
                        principal.tenantId(),
                        request.key(),
                        request.category(),
                        ChannelType.of(request.channel()),
                        request.language(),
                        request.subject(),
                        request.body(),
                        variablesOf(request.variables()))))
        .doOnNext(template -> log("created", template))
        .map(
            template ->
                ResponseEntity.created(
                        URI.create(
                            UriComponentsBuilder.fromPath("/templates/detail")
                                .queryParam("templateId", template.id().value())
                                .build()
                                .encode()
                                .toUriString()))
                    .body(TemplateResponse.from(template)));
  }

  @PutMapping("/templates")
  public Mono<TemplateResponse> update(
      final AuthenticatedPrincipal principal, @RequestBody final UpdateTemplateRequest request) {
    return Mono.defer(
            () -> {
              Preconditions.requireNonNull(request.version(), "version is required");
              return updateUseCase.update(
                  new UpdateTemplateCommand(
                      principal.tenantId(),
                      TemplateId.of(request.templateId()),
                      request.version(),
                      request.category(),
                      request.subject(),
                      request.body(),
                      variablesOf(request.variables())));
            })
        .doOnNext(template -> log("updated", template))
        .map(TemplateResponse::from);
  }

  @GetMapping("/templates")
  public Mono<TemplateListResponse> list(
      final AuthenticatedPrincipal principal,
      @RequestParam(name = "category", required = false) final String category,
      @RequestParam(name = "channel", required = false) final String channel,
      @RequestParam(name = "language", required = false) final String language,
      @RequestParam(name = "status", required = false) final String status,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size) {
    return Mono.defer(
            () ->
                queryUseCase.list(
                    new TemplateSearchCriteria(
                        principal.tenantId(),
                        category == null ? null : parse(TemplateCategory.class, category),
                        channel == null ? null : ChannelType.of(channel),
                        language,
                        status == null ? null : parse(TemplateStatus.class, status),
                        page,
                        size)))
        .map(TemplateListResponse::from);
  }

  @GetMapping("/templates/detail")
  public Mono<TemplateResponse> detail(
      final AuthenticatedPrincipal principal, @RequestParam("templateId") final String templateId) {
    return Mono.defer(() -> queryUseCase.get(principal.tenantId(), TemplateId.of(templateId)))
        .map(TemplateResponse::from);
  }

  @PostMapping("/template-archivals")
  public Mono<TemplateResponse> archive(
      final AuthenticatedPrincipal principal, @RequestBody final ArchiveTemplateRequest request) {
    return Mono.defer(
            () -> archiveUseCase.archive(principal.tenantId(), TemplateId.of(request.templateId())))
        .doOnNext(template -> log("archived", template))
        .map(TemplateResponse::from);
  }

  @PostMapping("/template-variable-detections")
  public Mono<DetectedTemplateVariablesResponse> detect(
      final AuthenticatedPrincipal principal,
      @RequestBody final DetectTemplateVariablesRequest request) {
    return Mono.defer(() -> detectUseCase.detect(request.subject(), request.body()))
        .map(DetectedTemplateVariablesResponse::new);
  }

  @PostMapping("/template-renders")
  public Mono<RenderedTemplateResponse> render(
      final AuthenticatedPrincipal principal, @RequestBody final RenderTemplateRequest request) {
    return Mono.defer(
            () ->
                renderUseCase.render(
                    principal.tenantId(), TemplateId.of(request.templateId()), request.values()))
        .map(RenderedTemplateResponse::from);
  }

  private static <E extends Enum<E>> E parse(final Class<E> type, final String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid " + type.getSimpleName() + " filter", e);
    }
  }

  private static List<TemplateVariable> variablesOf(final List<VariableDto> variables) {
    return variables == null ? List.of() : variables.stream().map(VariableDto::toDomain).toList();
  }

  private static void log(final String action, final Template template) {
    try (LogContext ignored =
        LogContext.open(null, LogSanitizer.safe(template.tenantId().value()), null)) {
      LOGGER.info(
          LogFields.fields(
              "action",
              action,
              "templateId",
              template.id().value(),
              "channel",
              template.channel().value(),
              "language",
              template.language(),
              "version",
              template.version()),
          "Template");
    }
  }
}
