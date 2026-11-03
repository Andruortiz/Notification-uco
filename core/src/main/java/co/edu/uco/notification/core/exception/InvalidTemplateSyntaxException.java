package co.edu.uco.notification.core.exception;

public class InvalidTemplateSyntaxException extends RuntimeException {

  public InvalidTemplateSyntaxException() {
    super("template text has invalid variable syntax");
  }
}
