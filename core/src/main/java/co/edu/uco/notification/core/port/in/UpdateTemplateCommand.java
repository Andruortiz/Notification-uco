package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.util.List;
import java.util.Map;

public record UpdateTemplateCommand(
    TenantId tenantId,
    TemplateId templateId,
    long version,
    TemplateCategory category,
    String subject,
    String body,
    List<TemplateVariable> variables,
    Map<String, String> sampleValues) {

  public UpdateTemplateCommand(
      final TenantId tenantId,
      final TemplateId templateId,
      final long version,
      final TemplateCategory category,
      final String subject,
      final String body,
      final List<TemplateVariable> variables) {
    this(tenantId, templateId, version, category, subject, body, variables, Map.of());
  }

  public UpdateTemplateCommand {
    variables = List.copyOf(variables);
    sampleValues = sampleValues == null ? Map.of() : Map.copyOf(sampleValues);
  }
}
