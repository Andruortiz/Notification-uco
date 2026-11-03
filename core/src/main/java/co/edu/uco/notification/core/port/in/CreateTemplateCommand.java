package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.util.List;
import java.util.Map;

public record CreateTemplateCommand(
    TenantId tenantId,
    String key,
    TemplateCategory category,
    ChannelType channel,
    String language,
    String subject,
    String body,
    List<TemplateVariable> variables,
    Map<String, String> sampleValues) {

  public CreateTemplateCommand(
      final TenantId tenantId,
      final String key,
      final TemplateCategory category,
      final ChannelType channel,
      final String language,
      final String subject,
      final String body,
      final List<TemplateVariable> variables) {
    this(tenantId, key, category, channel, language, subject, body, variables, Map.of());
  }

  public CreateTemplateCommand {
    variables = List.copyOf(variables);
    sampleValues = sampleValues == null ? Map.of() : Map.copyOf(sampleValues);
  }
}
