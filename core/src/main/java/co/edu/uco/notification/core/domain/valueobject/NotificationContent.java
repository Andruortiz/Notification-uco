package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record NotificationContent(String subject, String body) {

  public static final int MAX_LENGTH = 32_768;

  public NotificationContent {
    Preconditions.requireNonBlank(body, "NotificationContent body must not be blank");
    final int totalLength = (subject == null ? 0 : subject.length()) + body.length();
    Preconditions.requireTrue(
        totalLength <= MAX_LENGTH,
        "NotificationContent must not exceed " + MAX_LENGTH + " characters");
  }

  public static NotificationContent of(final String subject, final String body) {
    return new NotificationContent(subject, body);
  }

  public static NotificationContent of(final String body) {
    return new NotificationContent(null, body);
  }
}
