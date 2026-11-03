package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import java.util.List;
import java.util.Map;

public record PreviewCommand(
    ChannelType channel,
    String subject,
    String body,
    List<TemplateVariable> variables,
    Map<String, String> values) {

  public PreviewCommand {
    variables = variables == null ? null : List.copyOf(variables);
    values = values == null ? null : Map.copyOf(values);
  }
}
