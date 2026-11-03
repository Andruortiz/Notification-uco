package co.edu.uco.notification.core.domain.template;

import co.edu.uco.notification.utils.Preconditions;
import java.util.UUID;

public record TemplateId(String value) {

  public TemplateId {
    Preconditions.requireNonBlank(value, "TemplateId must not be blank");
  }

  public static TemplateId of(final String value) {
    return new TemplateId(value);
  }

  public static TemplateId newId() {
    return new TemplateId(UUID.randomUUID().toString());
  }
}
