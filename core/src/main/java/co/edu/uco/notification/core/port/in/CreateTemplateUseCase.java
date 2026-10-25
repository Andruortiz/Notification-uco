package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.Template;
import reactor.core.publisher.Mono;

public interface CreateTemplateUseCase {

  Mono<Template> create(CreateTemplateCommand command);
}
