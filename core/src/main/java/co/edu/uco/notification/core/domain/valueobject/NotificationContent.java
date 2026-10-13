package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record NotificationContent(String subject, String body, List<Attachment> attachments) {

  public static final int MAX_LENGTH = 32_768;

  public NotificationContent {
    Preconditions.requireNonBlank(body, "NotificationContent body must not be blank");
    final int totalLength = (subject == null ? 0 : subject.length()) + body.length();
    Preconditions.requireTrue(
        totalLength <= MAX_LENGTH,
        "NotificationContent must not exceed " + MAX_LENGTH + " characters");
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  public NotificationContent(final String subject, final String body) {
    this(subject, body, List.of());
  }

  public static NotificationContent of(
      final String subject, final String body, final List<Attachment> attachments) {
    return new NotificationContent(subject, body, attachments);
  }

  public static NotificationContent of(final String subject, final String body) {
    return new NotificationContent(subject, body);
  }

  public static NotificationContent of(final String body) {
    return new NotificationContent(null, body);
  }

  public boolean hasAttachments() {
    return !attachments.isEmpty();
  }
}
