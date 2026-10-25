package co.edu.uco.notification.core.exception;

import java.util.List;

public class UndeclaredTemplateVariableException extends RuntimeException {

  public UndeclaredTemplateVariableException(final List<String> names) {
    super("declared variables not present in the text: " + String.join(", ", names));
  }
}
