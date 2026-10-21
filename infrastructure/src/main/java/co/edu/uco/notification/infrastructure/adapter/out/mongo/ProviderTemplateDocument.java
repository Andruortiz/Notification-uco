package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.util.Map;

public record ProviderTemplateDocument(String templateId, Map<String, String> variables) {

  public ProviderTemplateDocument {
    variables = variables == null ? null : Map.copyOf(variables);
  }
}
