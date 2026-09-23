package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

public record BrevoEmailRequest(
    Sender sender,
    List<Contact> to,
    String subject,
    String textContent,
    Map<String, String> headers) {

  public BrevoEmailRequest {
    to = to == null ? null : List.copyOf(to);
    headers = headers == null ? null : Map.copyOf(headers);
  }

  @Override
  public List<Contact> to() {
    return to == null ? null : List.copyOf(to);
  }

  @Override
  public Map<String, String> headers() {
    return headers == null ? null : Map.copyOf(headers);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Sender(String email, String name) {}

  public record Contact(String email) {}
}
