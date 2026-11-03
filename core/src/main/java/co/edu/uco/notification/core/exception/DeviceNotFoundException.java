package co.edu.uco.notification.core.exception;

public class DeviceNotFoundException extends RuntimeException {

  public DeviceNotFoundException() {
    super("Device not found");
  }
}
