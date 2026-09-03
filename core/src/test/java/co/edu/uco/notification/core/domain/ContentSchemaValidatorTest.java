package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.exception.InvalidContentException;
import org.junit.jupiter.api.Test;

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
}
