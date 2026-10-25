package co.edu.uco.notification.core.domain.template;

import co.edu.uco.notification.utils.Preconditions;

public record TemplateVariable(String name, boolean required) {

  public TemplateVariable {
    Preconditions.requireNonBlank(name, "variable name must not be blank");
  }
}
