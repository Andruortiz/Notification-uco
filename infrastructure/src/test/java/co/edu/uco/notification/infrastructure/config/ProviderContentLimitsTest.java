package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderContentLimitsTest {

  @Test
  void everyEntryCarriesItsDocumentationUrlAndConsultationDate() {
    assertFalse(ProviderContentLimits.entries().isEmpty());
    for (final ProviderContentLimits.Entry entry : ProviderContentLimits.entries()) {
      assertTrue(entry.sourceUrl().startsWith("https://"), entry.toString());
      assertTrue(entry.consultedOn().isAfter(LocalDate.of(2026, 1, 1)), entry.toString());
      assertTrue(entry.value() > 0, entry.toString());
      assertFalse(entry.limitKey().isBlank(), entry.toString());
    }
  }

  @Test
  void containsOnlyTheValuesWithAVerifiedSource() {
    assertEquals(1, ProviderContentLimits.entries().size());
    final ProviderContentLimits.Entry twilio = ProviderContentLimits.entries().get(0);
    assertEquals("twilio", twilio.providerId());
    assertEquals("SMS", twilio.channel());
    assertEquals("body.maxLength", twilio.limitKey());
    assertEquals(1_600L, twilio.value());
    assertEquals("https://www.twilio.com/docs/messaging/api/message-resource", twilio.sourceUrl());
    assertEquals(LocalDate.of(2026, 10, 5), twilio.consultedOn());
  }

  @Test
  void byProviderGroupsTheEntriesByProviderAndChannel() {
    final Map<String, Map<String, Map<String, Long>>> table = ProviderContentLimits.byProvider();

    assertEquals(Map.of("twilio", Map.of("SMS", Map.of("body.maxLength", 1_600L))), table);
    assertFalse(table.containsKey("brevo"));
    assertFalse(table.containsKey("fcm"));
  }
}
