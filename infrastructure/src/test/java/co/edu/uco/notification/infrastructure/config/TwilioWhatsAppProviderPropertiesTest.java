package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TwilioWhatsAppProviderPropertiesTest {

  private static final String SID = "AC00000000000000000000000000000000";
  private static final String TOKEN = "secret-token-value";
  private static final String FROM = "+14155238886";

  private static TwilioWhatsAppProviderProperties with(
      final String sid, final String token, final String from) {
    return new TwilioWhatsAppProviderProperties(sid, token, from, null, null, null);
  }

  @Test
  void enabledWhenEverythingIsPresentAndWellFormed() {
    assertTrue(with(SID, TOKEN, FROM).disabledReason().isEmpty());
  }

  @Test
  void missingAccountSidNamesThePropertyAndTheVariable() {
    final String reason = with(null, TOKEN, FROM).disabledReason().orElseThrow();

    assertTrue(reason.contains("account-sid"));
    assertTrue(reason.contains("TWILIO_ACCOUNT_SID"));
  }

  @Test
  void missingAuthTokenNamesThePropertyAndTheVariable() {
    final String reason = with(SID, " ", FROM).disabledReason().orElseThrow();

    assertTrue(reason.contains("auth-token"));
    assertTrue(reason.contains("TWILIO_AUTH_TOKEN"));
  }

  @Test
  void missingFromNumberNamesThePropertyAndTheVariable() {
    final String reason = with(SID, TOKEN, "").disabledReason().orElseThrow();

    assertTrue(reason.contains("from-number"));
    assertTrue(reason.contains("TWILIO_WHATSAPP_FROM_NUMBER"));
  }

  @Test
  void malformedAccountSidIsReportedWithoutItsValue() {
    final String reason = with("ACnotHex", TOKEN, FROM).disabledReason().orElseThrow();

    assertTrue(reason.contains("malformed"));
    assertTrue(reason.contains("TWILIO_ACCOUNT_SID"));
    assertFalse(reason.contains("ACnotHex"));
  }

  @Test
  void malformedFromNumberIsReportedWithoutItsValue() {
    final String reason = with(SID, TOKEN, "14155238886").disabledReason().orElseThrow();

    assertTrue(reason.contains("malformed"));
    assertTrue(reason.contains("TWILIO_WHATSAPP_FROM_NUMBER"));
    assertFalse(reason.contains("14155238886"));
  }

  @Test
  void defaultsApplyWhenOptionalValuesAreAbsent() {
    final TwilioWhatsAppProviderProperties properties = with(SID, TOKEN, FROM);

    assertEquals("https://api.twilio.com", properties.baseUrl());
    assertEquals(10_000L, properties.timeoutMs());
    assertEquals(5_000L, properties.connectTimeoutMs());
  }

  @Test
  void toStringDoesNotExposeCredentials() {
    final String text = with(SID, TOKEN, FROM).toString();

    assertFalse(text.contains(SID));
    assertFalse(text.contains(TOKEN));
  }
}
