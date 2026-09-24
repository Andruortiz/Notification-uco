package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonInclude;

public record FcmSendRequest(Message message) {

  public static FcmSendRequest of(final String token, final String title, final String body) {
    return new FcmSendRequest(new Message(token, new Notification(title, body)));
  }

  public record Message(String token, Notification notification) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Notification(String title, String body) {}
}
