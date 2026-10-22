package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TwilioApiResponse(
    String sid, Integer code, String message, @JsonProperty("more_info") String moreInfo) {

  static final TwilioApiResponse EMPTY = new TwilioApiResponse(null, null, null, null);
}
