package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TwilioApiResponse(String sid, Integer code) {

  static final TwilioApiResponse EMPTY = new TwilioApiResponse(null, null);
}
