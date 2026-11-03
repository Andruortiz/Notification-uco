package co.edu.uco.notification.core.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.exception.InvalidRecipientException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecipientFormatPolicyTest {

  private static final ChannelType EMAIL = ChannelType.of("EMAIL");
  private static final ChannelType SMS = ChannelType.of("SMS");

  @Test
  void emailIsTrimmedLowercasedAndChecked() {
    assertEquals("a@b.co", RecipientFormatPolicy.validate(EMAIL, "  A@B.co "));
    assertThrows(
        InvalidRecipientException.class, () -> RecipientFormatPolicy.validate(EMAIL, "a@b"));
  }

  @Test
  void smsMustBeE164() {
    assertEquals("+573001234567", RecipientFormatPolicy.validate(SMS, " +573001234567"));
    assertThrows(
        InvalidRecipientException.class, () -> RecipientFormatPolicy.validate(SMS, "3001234567"));
  }

  @Test
  void otherChannelsAreOnlyTrimmed() {
    assertEquals(
        "Token-ABC", RecipientFormatPolicy.validate(ChannelType.of("PUSH"), " Token-ABC "));
  }

  @Test
  void tenThousandValidationsFinishInUnderOneSecond() {
    assertTimeout(
        Duration.ofSeconds(1),
        () -> {
          for (int i = 0; i < 10_000; i++) {
            RecipientFormatPolicy.validate(EMAIL, "user" + i + "@example.com");
            RecipientFormatPolicy.validate(SMS, "+57300123" + (1000 + i % 9000));
          }
        });
  }
}
