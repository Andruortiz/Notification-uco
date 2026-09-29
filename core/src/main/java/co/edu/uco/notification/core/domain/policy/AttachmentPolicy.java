package co.edu.uco.notification.core.domain.policy;

import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.utils.Preconditions;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class AttachmentPolicy {

  public static final int MAX_ATTACHMENTS = 5;
  public static final long MAX_SIZE_BYTES = 10_485_760L;
  public static final int MAX_FILE_NAME_LENGTH = 255;
  public static final int MAX_URL_LENGTH = 2048;
  public static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of(
          "application/pdf",
          "image/png",
          "image/jpeg",
          "image/gif",
          "image/webp",
          "text/plain",
          "text/csv",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private static final String INVALID_URL =
      "url must be an absolute https URL with a host and without user information";

  private AttachmentPolicy() {}

  public static void validate(final List<Attachment> attachments) {
    Preconditions.requireNonNull(attachments, "attachments must not be null");
    if (attachments.size() > MAX_ATTACHMENTS) {
      throw new InvalidAttachmentException(
          -1, "at most " + MAX_ATTACHMENTS + " attachments are allowed");
    }
    for (int position = 0; position < attachments.size(); position++) {
      validate(position, attachments.get(position));
    }
  }

  private static void validate(final int position, final Attachment attachment) {
    fileNameViolation(attachment.fileName())
        .ifPresent(
            rule -> {
              throw new InvalidAttachmentException(position, rule);
            });
    metadataViolation(attachment)
        .ifPresent(
            rule -> {
              throw new InvalidAttachmentException(position, rule, attachment.fileName());
            });
  }

  private static Optional<String> fileNameViolation(final String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return Optional.of("fileName is required");
    }
    if (fileName.length() > MAX_FILE_NAME_LENGTH) {
      return Optional.of("fileName must not exceed " + MAX_FILE_NAME_LENGTH + " characters");
    }
    if (fileName.chars().anyMatch(AttachmentPolicy::isForbiddenNameCharacter)) {
      return Optional.of("fileName must not contain path separators or control characters");
    }
    if (".".equals(fileName) || "..".equals(fileName)) {
      return Optional.of("fileName must not be . or ..");
    }
    return Optional.empty();
  }

  private static boolean isForbiddenNameCharacter(final int character) {
    return character == '/' || character == '\\' || Character.isISOControl(character);
  }

  private static Optional<String> metadataViolation(final Attachment attachment) {
    return contentTypeViolation(attachment.contentType())
        .or(() -> sizeViolation(attachment.sizeBytes()))
        .or(() -> urlViolation(attachment.url()));
  }

  private static Optional<String> contentTypeViolation(final String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return Optional.of("contentType is required");
    }
    if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
      return Optional.of("contentType " + contentType + " is not allowed");
    }
    return Optional.empty();
  }

  private static Optional<String> sizeViolation(final Long sizeBytes) {
    if (sizeBytes == null) {
      return Optional.of("sizeBytes is required");
    }
    if (sizeBytes < 1) {
      return Optional.of("sizeBytes must be at least 1");
    }
    if (sizeBytes > MAX_SIZE_BYTES) {
      return Optional.of("sizeBytes must not exceed " + MAX_SIZE_BYTES);
    }
    return Optional.empty();
  }

  private static Optional<String> urlViolation(final String url) {
    if (url == null || url.isBlank()) {
      return Optional.of("url is required");
    }
    if (url.length() > MAX_URL_LENGTH) {
      return Optional.of("url must not exceed " + MAX_URL_LENGTH + " characters");
    }
    return isAbsoluteHttpsWithoutUserInfo(url) ? Optional.empty() : Optional.of(INVALID_URL);
  }

  private static boolean isAbsoluteHttpsWithoutUserInfo(final String url) {
    try {
      final URI uri = new URI(url);
      return uri.getScheme() != null
          && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null;
    } catch (final URISyntaxException e) {
      return false;
    }
  }
}
