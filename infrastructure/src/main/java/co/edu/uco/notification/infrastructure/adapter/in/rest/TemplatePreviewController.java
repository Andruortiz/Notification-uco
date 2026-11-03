package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.port.in.PreviewCommand;
import co.edu.uco.notification.core.port.in.PreviewTemplateUseCase;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplateDtos.VariableDto;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplatePreviewDtos.PreviewTemplateRequest;
import co.edu.uco.notification.infrastructure.adapter.in.rest.TemplatePreviewDtos.TemplatePreviewResponse;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class TemplatePreviewController {

  private final PreviewTemplateUseCase previewUseCase;

  public TemplatePreviewController(final PreviewTemplateUseCase previewUseCase) {
    this.previewUseCase = Preconditions.requireNonNull(previewUseCase, "previewUseCase");
  }

  @PostMapping("/template-previews")
  public Mono<TemplatePreviewResponse> preview(
      final AuthenticatedPrincipal principal, @RequestBody final PreviewTemplateRequest request) {
    return Mono.defer(
            () ->
                previewUseCase.preview(
                    new PreviewCommand(
                        ChannelType.of(request.channel()),
                        request.subject(),
                        Preconditions.requireNonNull(request.body(), "body is required"),
                        variablesOf(request.variables()),
                        request.values())))
        .map(TemplatePreviewResponse::from);
  }

  private static List<TemplateVariable> variablesOf(final List<VariableDto> variables) {
    return variables == null ? List.of() : variables.stream().map(VariableDto::toDomain).toList();
  }
}
