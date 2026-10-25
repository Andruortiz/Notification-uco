package co.edu.uco.notification.core.exception;

public class TemplateVersionConflictException extends RuntimeException {

  public TemplateVersionConflictException() {
    super("the template was modified concurrently, reload and retry");
  }
}
