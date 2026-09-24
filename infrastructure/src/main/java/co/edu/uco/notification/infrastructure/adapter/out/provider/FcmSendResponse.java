package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FcmSendResponse(String name) {

  public static final FcmSendResponse EMPTY = new FcmSendResponse(null);

  public String messageId() {
    if (name == null) {
      return null;
    }
    return name.substring(name.lastIndexOf('/') + 1);
  }
}
