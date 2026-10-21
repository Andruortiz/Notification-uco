package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WhatsAppFailureReasonsTest {

  @ParameterizedTest
  @CsvSource({
    "63016,outside-session-window",
    "63024,invalid-recipient",
    "21211,invalid-recipient",
    "63015,recipient-not-in-sandbox",
    "63007,sender-not-configured",
    "63112,sender-not-configured",
    "20003,invalid-credentials"
  })
  void mapsEachKnownProviderCodeToItsReason(final int code, final String expected) {
    assertEquals(expected, WhatsAppFailureReasons.reasonOf(code));
  }

  @Test
  void anUnknownCodeIsUnclassified() {
    assertEquals("unclassified", WhatsAppFailureReasons.reasonOf(99999));
  }

  @Test
  void aMissingCodeIsUnclassified() {
    assertEquals("unclassified", WhatsAppFailureReasons.reasonOf(null));
  }
}
