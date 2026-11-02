package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.TemplatePreview;
import reactor.core.publisher.Mono;

public interface PreviewTemplateUseCase {

  Mono<TemplatePreview> preview(PreviewCommand command);
}
