package co.edu.uco.notification.infrastructure.adapter.in.rest;

public record RegisterDeviceRequest(String platform, String token, String subject) {

  @Override
  public String toString() {
    return "RegisterDeviceRequest[platform=" + platform + "]";
  }
}
