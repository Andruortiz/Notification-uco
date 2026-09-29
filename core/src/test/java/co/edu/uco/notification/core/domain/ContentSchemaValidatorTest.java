package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.policy.ContentSchemaValidator;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.exception.InvalidContentException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ContentSchemaValidatorTest {

  private static final ChannelType CHANNEL_TYPE = ChannelType.of("EMAIL");

  @Test
  void doesNothingWhenSchemaIsNull() {
    assertDoesNotThrow(
        () -> ContentSchemaValidator.validate(CHANNEL_TYPE, null, NotificationContent.of("Body")));
  }

  @Test
  void doesNothingWhenSchemaIsBlank() {
    assertDoesNotThrow(
        () -> ContentSchemaValidator.validate(CHANNEL_TYPE, "  ", NotificationContent.of("Body")));
  }

  @Test
  void doesNothingWhenContentMatchesTheSchema() {
    assertDoesNotThrow(
        () ->
            ContentSchemaValidator.validate(
                CHANNEL_TYPE,
                "{\"type\":\"object\",\"required\":[\"body\"]}",
                NotificationContent.of("Body")));
  }

  @Test
  void throwsInvalidContentExceptionWhenContentDoesNotMatchTheSchema() {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                ContentSchemaValidator.validate(
                    CHANNEL_TYPE,
                    "{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\",\"minLength\":1}}}",
                    NotificationContent.of("Body")));

    assertTrue(exception.getMessage().contains("EMAIL"));
  }

  private static final String SECRET = "secret-token-9911";

  private static final String ATTACHMENTS_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\"},"
          + "\"attachments\":{\"type\":\"array\",\"maxItems\":2,\"items\":{\"type\":\"object\","
          + "\"properties\":{\"contentType\":{\"enum\":[\"application/pdf\",\"image/png\"]},"
          + "\"sizeBytes\":{\"maximum\":5242880}}}}}}";

  private static Attachment attachment(final String contentType, final long sizeBytes) {
    return Attachment.of(
        "invoice.pdf",
        contentType,
        sizeBytes,
        "https://files.example.test/" + SECRET + "/invoice.pdf");
  }

  private static NotificationContent withAttachments(final Attachment... attachments) {
    return NotificationContent.of("Subject", "Body", List.of(attachments));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "  ",
        "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":160}}}",
        "{\"type\":\"object\",\"items\":{\"properties\":{\"attachments\":{}}}}"
      })
  void rejectsAttachmentsWhenTheSchemaDoesNotDeclareThem(final String schema) {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                ContentSchemaValidator.validate(
                    CHANNEL_TYPE, schema, withAttachments(attachment("application/pdf", 10))));

    assertTrue(exception.getMessage().contains("EMAIL"), exception.getMessage());
    assertTrue(
        exception.getMessage().contains("channel does not accept attachments"),
        exception.getMessage());
  }

  @Test
  void acceptsAttachmentsWithinTheChannelDeclarationIncludingItsLimits() {
    assertDoesNotThrow(
        () ->
            ContentSchemaValidator.validate(
                CHANNEL_TYPE,
                ATTACHMENTS_SCHEMA,
                withAttachments(
                    attachment("application/pdf", 5_242_880), attachment("image/png", 1))));
  }

  @Test
  void acceptsContentWithoutAttachmentsOnAChannelThatDeclaresThem() {
    assertDoesNotThrow(
        () ->
            ContentSchemaValidator.validate(
                CHANNEL_TYPE, ATTACHMENTS_SCHEMA, NotificationContent.of("Body")));
  }

  @Test
  void rejectsMoreAttachmentsThanTheChannelAllows() {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                ContentSchemaValidator.validate(
                    CHANNEL_TYPE,
                    ATTACHMENTS_SCHEMA,
                    withAttachments(
                        attachment("application/pdf", 1),
                        attachment("application/pdf", 1),
                        attachment("application/pdf", 1))));

    assertTrue(exception.getMessage().contains("attachments"), exception.getMessage());
    assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
  }

  @Test
  void rejectsATypeTheChannelDoesNotAllowNamingThePosition() {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                ContentSchemaValidator.validate(
                    CHANNEL_TYPE,
                    ATTACHMENTS_SCHEMA,
                    withAttachments(attachment("application/pdf", 1), attachment("image/gif", 1))));

    assertTrue(exception.getMessage().contains("attachments[1]"), exception.getMessage());
    assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
  }

  @Test
  void rejectsASizeAboveTheChannelMaximum() {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                ContentSchemaValidator.validate(
                    CHANNEL_TYPE,
                    ATTACHMENTS_SCHEMA,
                    withAttachments(attachment("application/pdf", 5_242_881))));

    assertTrue(exception.getMessage().contains("attachments[0]"), exception.getMessage());
    assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
  }

  @Test
  void neverExposesTheUrlToTheSchema() {
    final String schemaRequiringUrl =
        "{\"type\":\"object\",\"properties\":{\"attachments\":{\"type\":\"array\","
            + "\"items\":{\"required\":[\"url\"]}}}}";

    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                ContentSchemaValidator.validate(
                    CHANNEL_TYPE,
                    schemaRequiringUrl,
                    withAttachments(attachment("application/pdf", 1))));

    assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
  }
}
