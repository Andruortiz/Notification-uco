package co.edu.uco.notification.core.exception;

public class TemplateNotFoundException extends RuntimeException {

  public TemplateNotFoundException() {
    super("template not found");
  }
}
