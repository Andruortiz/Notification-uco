package co.edu.uco.notification.core.domain.valueobject;

import java.util.Locale;

public record Attachment(String fileName, String contentType, Long sizeBytes, String url) {

  public Attachment {
    contentType = normalize(contentType);
  }

  public static Attachment of(
      final String fileName, final String contentType, final Long sizeBytes, final String url) {
    return new Attachment(fileName, contentType, sizeBytes, url);
  }

  private static String normalize(final String contentType) {
    if (contentType == null) {
      return null;
    }
    final int parameters = contentType.indexOf(';');
    final String mediaType = parameters < 0 ? contentType : contentType.substring(0, parameters);
    return mediaType.strip().toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return "Attachment[fileName="
        + fileName
        + ", contentType="
        + contentType
        + ", sizeBytes="
        + sizeBytes
        + "]";
  }
}
