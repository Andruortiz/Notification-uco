package co.edu.uco.notification.core.domain.valueobject;

import java.util.Locale;

public record AttachmentSubmission(
    String fileName, String contentType, Long sizeBytes, String content, String url) {

  public AttachmentSubmission {
    contentType = normalizeContentType(contentType);
  }

  public static AttachmentSubmission embedded(
      final String fileName, final String contentType, final Long sizeBytes, final String content) {
    return new AttachmentSubmission(fileName, contentType, sizeBytes, content, null);
  }

  public static AttachmentSubmission reference(
      final String fileName, final String contentType, final Long sizeBytes, final String url) {
    return new AttachmentSubmission(fileName, contentType, sizeBytes, null, url);
  }

  public static String normalizeContentType(final String contentType) {
    if (contentType == null) {
      return null;
    }
    final int parameters = contentType.indexOf(';');
    final String mediaType = parameters < 0 ? contentType : contentType.substring(0, parameters);
    return mediaType.strip().toLowerCase(Locale.ROOT);
  }

  public boolean isEmbedded() {
    return content != null && url == null;
  }

  public boolean isReference() {
    return url != null && content == null;
  }

  @Override
  public String toString() {
    return "AttachmentSubmission[fileName="
        + fileName
        + ", contentType="
        + contentType
        + ", sizeBytes="
        + sizeBytes
        + "]";
  }
}
