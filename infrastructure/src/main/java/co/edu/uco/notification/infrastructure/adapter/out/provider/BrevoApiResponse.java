package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record BrevoApiResponse(String messageId) {

  static final BrevoApiResponse EMPTY = new BrevoApiResponse(null);
}
