package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.policy.ContentSchemaValidator;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
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
  private static final NotificationContent BODY_ONLY = NotificationContent.of("Body");
  private static final NotificationContent SUBJECT_AND_BODY =
      NotificationContent.of("Subject", "Body");

  @Test
  void doesNothingWhenSchemaIsNull() {
    assertDoesNotThrow(
        () -> ContentSchemaValidator.validate(CHANNEL_TYPE, null, BODY_ONLY, List.of()));
  }

  @Test
  void doesNothingWhenSchemaIsBlank() {
    assertDoesNotThrow(
        () -> ContentSchemaValidator.validate(CHANNEL_TYPE, "  ", BODY_ONLY, List.of()));
  }

  @Test
  void doesNothingWhenContentMatchesTheSchema() {
    assertDoesNotThrow(
        () ->
            ContentSchemaValidator.validate(
                CHANNEL_TYPE,
                "{\"type\":\"object\",\"required\":[\"body\"]}",
                BODY_ONLY,
                List.of()));
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
                    BODY_ONLY,
                    List.of()));

    assertTrue(exception.getMessage().contains("EMAIL"));
  }

  private static final String SECRET = "secret-token-9911";
  private static final String CONTENT_MARKER = "c2VjcmV0LWNvbnRlbnQtOTkxMQ==";

  private static final String ATTACHMENTS_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\"},"
          + "\"attachments\":{\"type\":\"array\",\"maxItems\":2,\"items\":{\"type\":\"object\","
          + "\"properties\":{\"contentType\":{\"enum\":[\"application/pdf\",\"image/png\"]},"
          + "\"sizeBytes\":{\"maximum\":5242880}}}}}}";

  private static AttachmentSubmission attachment(final String contentType, final long sizeBytes) {
    return sizeBytes <= 1_048_576L
        ? AttachmentSubmission.embedded("invoice.pdf", contentType, sizeBytes, CONTENT_MARKER)
        : AttachmentSubmission.reference(
            "invoice.pdf", contentType, sizeBytes, "http://minio.test/b/u?sig=" + SECRET);
  }

  private static void validate(final String schema, final AttachmentSubmission... attachments) {
    ContentSchemaValidator.validate(CHANNEL_TYPE, schema, SUBJECT_AND_BODY, List.of(attachments));
  }

  private static void assertNoSensitiveData(final Exception exception) {
    assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
    assertFalse(exception.getMessage().contains(CONTENT_MARKER), exception.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "  ",
        "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":160}}}",
        "{\"type\":\"object\",\"items\":{\"properties\":{\"attachments\":{}}}}",
        "not json"
      })
  void rejectsAttachmentsWhenTheSchemaDoesNotDeclareThem(final String schema) {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () -> validate(schema, attachment("application/pdf", 10)));

    assertTrue(exception.getMessage().contains("EMAIL"), exception.getMessage());
    assertTrue(
        exception.getMessage().contains("channel does not accept attachments"),
        exception.getMessage());
  }

  @Test
  void acceptsAttachmentsWithinTheChannelDeclarationIncludingItsLimits() {
    assertDoesNotThrow(
        () ->
            validate(
                ATTACHMENTS_SCHEMA,
                attachment("application/pdf", 5_242_880),
                attachment("image/png", 1)));
  }

  @Test
  void acceptsContentWithoutAttachmentsOnAChannelThatDeclaresThem() {
    assertDoesNotThrow(
        () ->
            ContentSchemaValidator.validate(
                CHANNEL_TYPE, ATTACHMENTS_SCHEMA, BODY_ONLY, List.of()));
  }

  @Test
  void rejectsMoreAttachmentsThanTheChannelAllows() {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                validate(
                    ATTACHMENTS_SCHEMA,
                    attachment("application/pdf", 1),
                    attachment("application/pdf", 1),
                    attachment("application/pdf", 1)));

    assertTrue(exception.getMessage().contains("attachments"), exception.getMessage());
    assertNoSensitiveData(exception);
  }

  @Test
  void rejectsATypeTheChannelDoesNotAllowNamingThePosition() {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                validate(
                    ATTACHMENTS_SCHEMA,
                    attachment("application/pdf", 1),
                    attachment("image/jpeg", 1)));

    assertTrue(exception.getMessage().contains("attachments[1]"), exception.getMessage());
    assertNoSensitiveData(exception);
  }

  @Test
  void rejectsASizeAboveTheChannelMaximum() {
    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () -> validate(ATTACHMENTS_SCHEMA, attachment("application/pdf", 5_242_881)));

    assertTrue(exception.getMessage().contains("attachments[0]"), exception.getMessage());
    assertNoSensitiveData(exception);
  }

  @ParameterizedTest
  @ValueSource(strings = {"url", "content"})
  void neverExposesTheContentNorTheUrlToTheSchema(final String field) {
    final String schemaRequiringField =
        "{\"type\":\"object\",\"properties\":{\"attachments\":{\"type\":\"array\","
            + "\"items\":{\"required\":[\""
            + field
            + "\"]}}}}";

    final InvalidContentException exception =
        assertThrows(
            InvalidContentException.class,
            () ->
                validate(
                    schemaRequiringField,
                    attachment("application/pdf", 1),
                    attachment("application/pdf", 2_000_000)));

    assertNoSensitiveData(exception);
  }
}
