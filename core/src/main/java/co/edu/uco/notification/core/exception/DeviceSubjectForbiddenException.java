package co.edu.uco.notification.core.exception;

public class DeviceSubjectForbiddenException extends RuntimeException {

  public DeviceSubjectForbiddenException() {
    super("The requested subject is not allowed for this session");
  }
}
