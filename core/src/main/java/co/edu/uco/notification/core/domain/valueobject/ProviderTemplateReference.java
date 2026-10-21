package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderTemplateReference(String templateId, Map<String, String> variables) {

  public ProviderTemplateReference {
    Preconditions.requireNonBlank(
        templateId, "ProviderTemplateReference templateId must not be blank");
    final Map<String, String> copy = new LinkedHashMap<>();
    if (variables != null) {
      variables.forEach(
          (name, value) -> {
            Preconditions.requireNonBlank(
                name, "ProviderTemplateReference variable name must not be blank");
            Preconditions.requireNonNull(
                value, "ProviderTemplateReference variable value must not be null");
            copy.put(name, value);
          });
    }
    variables = java.util.Collections.unmodifiableMap(copy);
  }

  public static ProviderTemplateReference of(
      final String templateId, final Map<String, String> variables) {
    return new ProviderTemplateReference(templateId, variables);
  }

  @Override
  public String toString() {
    return "ProviderTemplateReference[templateId="
        + templateId
        + ", variables="
        + variables.size()
        + "]";
  }
}
