package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.ProviderTemplateReference;
import java.util.Map;

public record ProviderTemplateRequest(String templateId, Map<String, String> variables) {

  ProviderTemplateReference toReference() {
    return ProviderTemplateReference.of(templateId, variables);
  }

  @Override
  public String toString() {
    return "ProviderTemplateRequest[templateId="
        + templateId
        + ", variables="
        + (variables == null ? 0 : variables.size())
        + "]";
  }
}
