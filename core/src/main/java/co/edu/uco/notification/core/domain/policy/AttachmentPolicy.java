package co.edu.uco.notification.core.domain.policy;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class AttachmentPolicy {

  public static final int MAX_ATTACHMENTS = 5;
  public static final long MAX_SIZE_BYTES = 10_485_760L;
  public static final long EMBEDDED_MAX_SIZE_BYTES = 1_048_576L;
  public static final long MAX_TOTAL_SIZE_BYTES = 26_214_400L;
  public static final int MAX_FILE_NAME_LENGTH = 255;
  public static final int MAX_URL_LENGTH = 2048;
  public static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of(
          "application/pdf",
          "image/png",
          "image/jpeg",
          "text/plain",
          "text/csv",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  public static final List<String> FORBIDDEN_EXTENSIONS =
      List.of(
          ".exe",
          ".msi",
          ".bat",
          ".cmd",
          ".com",
          ".scr",
          ".pif",
          ".vbs",
          ".vbe",
          ".js",
          ".jse",
          ".wsf",
          ".wsh",
          ".ps1",
          ".hta",
          ".cpl",
          ".msc",
          ".reg",
          ".lnk",
          ".jar",
          ".dll",
          ".sh",
          ".apk",
          ".app",
          ".gadget",
          ".com.pif",
          ".msix",
          ".war",
          ".docm",
          ".xlsm",
          ".html",
          ".htm",
          ".svg",
          ".iso");

  private static final String FORBIDDEN_EXTENSION = "fileName extension is not allowed";
  private static final String INVALID_BASE64 = "content must be valid Base64";

  private AttachmentPolicy() {}

  public static void validate(final List<AttachmentSubmission> submissions) {
    Preconditions.requireNonNull(submissions, "attachments must not be null");
    if (submissions.size() > MAX_ATTACHMENTS) {
      throw new InvalidAttachmentException(
          -1, "at most " + MAX_ATTACHMENTS + " attachments are allowed");
    }
    long totalSize = 0;
    for (int position = 0; position < submissions.size(); position++) {
      final AttachmentSubmission submission = submissions.get(position);
      validate(position, submission);
      totalSize += submission.sizeBytes();
    }
    if (totalSize > MAX_TOTAL_SIZE_BYTES) {
      throw new InvalidAttachmentException(
          -1,
          "the total size of the attachments must not exceed " + MAX_TOTAL_SIZE_BYTES + " bytes");
    }
  }

  public static void validateUploadRequest(
      final String fileName, final String contentType, final Long sizeBytes) {
    fileNameViolation(fileName)
        .ifPresent(
            rule -> {
              throw InvalidAttachmentException.forUpload(rule);
            });
    contentTypeViolation(AttachmentSubmission.normalizeContentType(contentType))
        .or(() -> sizeViolation(sizeBytes))
        .or(() -> uploadSizeViolation(sizeBytes))
        .ifPresent(
            rule -> {
              throw InvalidAttachmentException.forUpload(rule, fileName);
            });
  }

  public static byte[] decode(final int position, final AttachmentSubmission submission) {
    return decodeStrict(submission.content())
        .orElseThrow(
            () -> new InvalidAttachmentException(position, INVALID_BASE64, submission.fileName()));
  }

  private static void validate(final int position, final AttachmentSubmission submission) {
    fileNameViolation(submission.fileName())
        .ifPresent(
            rule -> {
              throw new InvalidAttachmentException(position, rule);
            });
    contentTypeViolation(submission.contentType())
        .or(() -> sizeViolation(submission.sizeBytes()))
        .or(() -> formViolation(submission))
        .ifPresent(
            rule -> {
              throw new InvalidAttachmentException(position, rule, submission.fileName());
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
    if (hasForbiddenExtension(fileName)) {
      return Optional.of(FORBIDDEN_EXTENSION);
    }
    return Optional.empty();
  }

  private static boolean hasForbiddenExtension(final String fileName) {
    final String normalized = stripTrailingDotsAndSpaces(fileName.toLowerCase(Locale.ROOT));
    return FORBIDDEN_EXTENSIONS.stream().anyMatch(normalized::endsWith);
  }

  private static String stripTrailingDotsAndSpaces(final String value) {
    int end = value.length();
    while (end > 0 && isTrailingNoise(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }

  private static boolean isTrailingNoise(final char character) {
    return character == '.' || Character.isWhitespace(character);
  }

  private static boolean isForbiddenNameCharacter(final int character) {
    return character == '/' || character == '\\' || Character.isISOControl(character);
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

  private static Optional<String> uploadSizeViolation(final Long sizeBytes) {
    if (sizeBytes <= EMBEDDED_MAX_SIZE_BYTES) {
      return Optional.of(
          "sizeBytes must be larger than "
              + EMBEDDED_MAX_SIZE_BYTES
              + " for an upload; smaller files travel embedded");
    }
    return Optional.empty();
  }

  private static Optional<String> formViolation(final AttachmentSubmission submission) {
    if (submission.isEmbedded()) {
      return embeddedViolation(submission);
    }
    if (submission.isReference()) {
      return referenceViolation(submission);
    }
    return Optional.of("exactly one of content or url is required");
  }

  private static Optional<String> embeddedViolation(final AttachmentSubmission submission) {
    if (submission.sizeBytes() > EMBEDDED_MAX_SIZE_BYTES) {
      return Optional.of(
          "content is only allowed for files of up to " + EMBEDDED_MAX_SIZE_BYTES + " bytes");
    }
    final Optional<byte[]> decoded = decodeStrict(submission.content());
    if (decoded.isEmpty()) {
      return Optional.of(INVALID_BASE64);
    }
    if (decoded.get().length != submission.sizeBytes()) {
      return Optional.of("sizeBytes does not match the decoded content");
    }
    return Optional.empty();
  }

  private static Optional<String> referenceViolation(final AttachmentSubmission submission) {
    if (submission.sizeBytes() <= EMBEDDED_MAX_SIZE_BYTES) {
      return Optional.of(
          "url is only allowed for files larger than " + EMBEDDED_MAX_SIZE_BYTES + " bytes");
    }
    if (submission.url().length() > MAX_URL_LENGTH) {
      return Optional.of("url must not exceed " + MAX_URL_LENGTH + " characters");
    }
    return Optional.empty();
  }

  private static Optional<byte[]> decodeStrict(final String content) {
    if (content == null || content.isEmpty() || content.length() % 4 != 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(Base64.getDecoder().decode(content));
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
