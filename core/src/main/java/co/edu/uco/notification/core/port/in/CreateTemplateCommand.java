package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.util.List;

public record CreateTemplateCommand(
    TenantId tenantId,
    String key,
    TemplateCategory category,
    ChannelType channel,
    String language,
    String subject,
    String body,
    List<TemplateVariable> variables) {

  public CreateTemplateCommand {
    variables = List.copyOf(variables);
  }
}
