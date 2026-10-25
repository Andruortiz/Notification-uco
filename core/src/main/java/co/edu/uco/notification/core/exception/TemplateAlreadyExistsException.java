package co.edu.uco.notification.core.exception;

public class TemplateAlreadyExistsException extends RuntimeException {

  public TemplateAlreadyExistsException() {
    super("a template with this key, channel and language already exists");
  }
}
