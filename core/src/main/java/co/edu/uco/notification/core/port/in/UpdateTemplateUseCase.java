package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.Template;
import reactor.core.publisher.Mono;

public interface UpdateTemplateUseCase {

  Mono<Template> update(UpdateTemplateCommand command);
}
