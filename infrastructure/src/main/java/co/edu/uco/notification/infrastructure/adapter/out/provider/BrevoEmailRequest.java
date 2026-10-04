package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

public record BrevoEmailRequest(
    Sender sender,
    List<Contact> to,
    String subject,
    String textContent,
    Map<String, String> headers,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Attachment> attachment) {

  public BrevoEmailRequest {
    to = to == null ? null : List.copyOf(to);
    headers = headers == null ? null : Map.copyOf(headers);
    attachment = attachment == null ? null : List.copyOf(attachment);
  }

  public BrevoEmailRequest(
      final Sender sender,
      final List<Contact> to,
      final String subject,
      final String textContent,
      final Map<String, String> headers) {
    this(sender, to, subject, textContent, headers, null);
  }

  @Override
  public List<Contact> to() {
    return to == null ? null : List.copyOf(to);
  }

  @Override
  public Map<String, String> headers() {
    return headers == null ? null : Map.copyOf(headers);
  }

  @Override
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public List<Attachment> attachment() {
    return attachment == null ? null : List.copyOf(attachment);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Sender(String email, String name) {}

  public record Contact(String email) {}

  public record Attachment(String name, String content) {

    @Override
    public String toString() {
      return "Attachment[name=" + name + "]";
    }
  }
}
