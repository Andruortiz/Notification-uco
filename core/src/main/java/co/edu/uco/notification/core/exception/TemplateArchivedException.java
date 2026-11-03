package co.edu.uco.notification.core.exception;

public class TemplateArchivedException extends RuntimeException {

  public TemplateArchivedException() {
    super("the template is archived");
  }
}
