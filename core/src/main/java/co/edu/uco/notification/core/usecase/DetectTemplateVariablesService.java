package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateEngine;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.port.in.DetectTemplateVariablesUseCase;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import reactor.core.publisher.Mono;

public final class DetectTemplateVariablesService implements DetectTemplateVariablesUseCase {

  @Override
  public Mono<List<String>> detect(final String subject, final String body) {
    return Mono.fromSupplier(
        () -> {
          Preconditions.requireNonNull(body, "body must not be null");
          if (body.length() > Template.MAX_BODY
              || (subject != null && subject.length() > Template.MAX_SUBJECT)) {
            throw new TemplateLimitExceededException("text exceeds the allowed length");
          }
          return List.copyOf(TemplateEngine.detect(subject, body));
        });
  }
}
