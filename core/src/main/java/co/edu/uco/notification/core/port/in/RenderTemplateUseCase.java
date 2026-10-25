package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.util.Map;
import reactor.core.publisher.Mono;

public interface RenderTemplateUseCase {

  Mono<RenderedTemplate> render(
      TenantId tenantId, TemplateId templateId, Map<String, String> values);
}
