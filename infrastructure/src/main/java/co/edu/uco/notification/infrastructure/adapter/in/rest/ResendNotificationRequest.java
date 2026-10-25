package co.edu.uco.notification.infrastructure.adapter.in.rest;

public record ResendNotificationRequest(
    String notificationId, String channelType, String recipient) {

  @Override
  public String toString() {
    return "ResendNotificationRequest[notificationId=" + notificationId + "]";
  }
}
