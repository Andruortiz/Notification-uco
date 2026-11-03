package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.Template;
import java.util.List;

public record TemplatePage(List<Template> items, int page, int size, boolean hasNext) {

  public TemplatePage {
    items = List.copyOf(items);
  }
}
