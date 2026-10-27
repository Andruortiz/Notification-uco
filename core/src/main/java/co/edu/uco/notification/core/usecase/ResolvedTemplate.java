package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import java.util.List;

public record ResolvedTemplate(
    String subject,
    String body,
    ChannelType channelType,
    TemplateCategory category,
    Boolean critical,
    List<TemplateVariable> variables,
    List<String> blockRefs) {

  public ResolvedTemplate {
    variables = List.copyOf(variables);
    blockRefs = List.copyOf(blockRefs);
  }
}
