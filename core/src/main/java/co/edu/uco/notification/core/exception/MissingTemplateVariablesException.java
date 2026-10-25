package co.edu.uco.notification.core.exception;

import java.util.List;

public class MissingTemplateVariablesException extends RuntimeException {

  private final transient List<String> missing;

  public MissingTemplateVariablesException(final List<String> missing) {
    super("required template variables are missing");
    this.missing = List.copyOf(missing);
  }

  public List<String> missing() {
    return missing;
  }
}
