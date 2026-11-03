package co.edu.uco.notification.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressesTest {

  @ParameterizedTest
  @ValueSource(strings = {"alice@example.com", "  Alice.B+tag@Mail.Example.CO ", "a@b-c.io"})
  void acceptsPragmaticAddresses(final String value) {
    assertTrue(EmailAddresses.isValid(value));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "alice",
        "alice@",
        "@example.com",
        "alice@example",
        "al ice@example.com",
        "alice@@example.com",
        "alice@example..com",
        "alice@-.com.",
        "alice@exa_mple.com"
      })
  void rejectsMalformedAddresses(final String value) {
    assertFalse(EmailAddresses.isValid(value));
  }

  @Test
  void rejectsNullAndTooLongAddresses() {
    assertFalse(EmailAddresses.isValid(null));
    assertFalse(EmailAddresses.isValid("a".repeat(250) + "@example.com"));
  }

  @Test
  void normalizesByTrimmingAndLowercasing() {
    assertEquals("alice@example.com", EmailAddresses.normalize("  Alice@Example.COM "));
    assertNull(EmailAddresses.normalize(null));
  }

  @Test
  void masksTheLocalPartKeepingTheDomain() {
    assertEquals("a***@example.com", EmailAddresses.mask("alice@example.com"));
    assertEquals("***", EmailAddresses.mask("not-an-email"));
    assertEquals("***", EmailAddresses.mask(null));
  }

  @Test
  void tenThousandValidationsFinishInUnderOneSecond() {
    assertTimeout(
        Duration.ofSeconds(1),
        () -> {
          for (int i = 0; i < 10_000; i++) {
            EmailAddresses.isValid("user" + i + "@example.com");
            EmailAddresses.isValid("bad address " + i);
          }
        });
  }
}
