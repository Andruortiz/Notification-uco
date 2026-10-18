package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.uco.notification.core.domain.policy.ContentSchemaValidator;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentSchemaMessageReferenceTest {

  private static final String ATTACHMENTS =
      "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\"},"
          + "\"attachments\":{\"type\":\"array\",\"maxItems\":2,\"items\":{\"type\":\"object\","
          + "\"properties\":{\"contentType\":{\"enum\":[\"application/pdf\",\"image/png\"]},"
          + "\"sizeBytes\":{\"maximum\":5242880}}}}}}";
  private static final String TOTAL =
      "{\"type\":\"object\",\"properties\":{\"attachments\":{\"type\":\"array\"},"
          + "\"attachmentsTotalBytes\":{\"type\":\"integer\",\"maximum\":4000000}}}";
  private static final String SMS =
      "{\"type\":\"object\",\"required\":[\"body\"],"
          + "\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":3}}}";
  private static final String PUSH =
      "{\"type\":\"object\",\"required\":[\"body\"],"
          + "\"properties\":{\"subject\":{\"maxLength\":2},\"body\":{\"type\":\"string\"}}}";
  private static final String MIN =
      "{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\",\"minLength\":1}}}";

  private static AttachmentSubmission attachment(final String contentType, final long size) {
    return AttachmentSubmission.embedded("invoice.pdf", contentType, size, "c2VjcmV0");
  }

  private static String message(
      final String schema, final NotificationContent content, final List<AttachmentSubmission> at) {
    try {
      ContentSchemaValidator.validate(ChannelType.of("EMAIL"), schema, content, at);
      return "OK";
    } catch (final RuntimeException e) {
      return e.getMessage();
    }
  }

  private static Map<String, String> reference(final String language) throws IOException {
    final Map<String, String> reference = new LinkedHashMap<>();
    try (InputStream stream =
        ContentSchemaMessageReferenceTest.class.getResourceAsStream(
            "/reference/messages-" + language + ".tsv")) {
      final String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      for (final String line : content.split("\r?\n")) {
        if (!line.isBlank()) {
          final int tab = line.indexOf('\t');
          reference.put(line.substring(0, tab), line.substring(tab + 1));
        }
      }
    }
    return reference;
  }

  @Test
  void theMessagesSentToTheClientAreTheSameAsBeforeTheMigration() throws IOException {
    final String language = Locale.getDefault().getLanguage();
    assumeTrue(language.equals("en") || language.equals("es"), language);
    final NotificationContent body = NotificationContent.of("Body");
    final Map<String, String> actual = new LinkedHashMap<>();
    actual.put(
        "maxItems",
        message(
            ATTACHMENTS,
            body,
            List.of(
                attachment("application/pdf", 1),
                attachment("application/pdf", 1),
                attachment("application/pdf", 1))));
    actual.put(
        "enum",
        message(
            ATTACHMENTS,
            body,
            List.of(attachment("application/pdf", 1), attachment("image/jpeg", 1))));
    actual.put(
        "maximum", message(ATTACHMENTS, body, List.of(attachment("application/pdf", 5242881))));
    actual.put(
        "total",
        message(
            TOTAL,
            body,
            List.of(
                attachment("application/pdf", 2500000), attachment("application/pdf", 2500000))));
    actual.put("smsMaxLength", message(SMS, NotificationContent.of("Body"), List.of()));
    actual.put("pushSubject", message(PUSH, NotificationContent.of("Subject", "Body"), List.of()));
    actual.put("minLength", message(MIN, body, List.of()));
    actual.put(
        "noAttachmentsDeclared",
        message("{\"type\":\"object\"}", body, List.of(attachment("application/pdf", 1))));

    assertEquals(reference(language), actual);
  }
}
