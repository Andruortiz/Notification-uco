package co.edu.uco.notification.core.port.in;

import java.util.List;
import reactor.core.publisher.Mono;

public interface DetectTemplateVariablesUseCase {

  Mono<List<String>> detect(String subject, String body);
}
