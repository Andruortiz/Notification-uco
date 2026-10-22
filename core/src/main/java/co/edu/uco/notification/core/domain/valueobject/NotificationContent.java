package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record NotificationContent(
    String subject,
    String body,
    List<Attachment> attachments,
    ProviderTemplateReference providerTemplate) {

  public static final int MAX_LENGTH = 32_768;

  public NotificationContent {
    if (providerTemplate == null) {
      Preconditions.requireNonBlank(body, "NotificationContent body must not be blank");
    } else {
      Preconditions.requireTrue(
          body == null || body.isBlank(),
          "NotificationContent body and providerTemplate are mutually exclusive");
      body = null;
    }
    final int totalLength =
        (subject == null ? 0 : subject.length()) + (body == null ? 0 : body.length());
    Preconditions.requireTrue(
        totalLength <= MAX_LENGTH,
        "NotificationContent must not exceed " + MAX_LENGTH + " characters");
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  public NotificationContent(
      final String subject, final String body, final List<Attachment> attachments) {
    this(subject, body, attachments, null);
  }

  public NotificationContent(final String subject, final String body) {
    this(subject, body, List.of(), null);
  }

  public static NotificationContent of(
      final String subject, final String body, final List<Attachment> attachments) {
    return new NotificationContent(subject, body, attachments, null);
  }

  public static NotificationContent of(
      final String subject,
      final String body,
      final List<Attachment> attachments,
      final ProviderTemplateReference providerTemplate) {
    return new NotificationContent(subject, body, attachments, providerTemplate);
  }

  public static NotificationContent ofProviderTemplate(
      final String subject, final ProviderTemplateReference providerTemplate) {
    Preconditions.requireNonNull(providerTemplate, "providerTemplate must not be null");
    return new NotificationContent(subject, null, List.of(), providerTemplate);
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

  public boolean hasProviderTemplate() {
    return providerTemplate != null;
  }
}
