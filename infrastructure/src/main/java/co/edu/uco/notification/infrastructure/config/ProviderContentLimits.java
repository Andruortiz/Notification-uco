package co.edu.uco.notification.infrastructure.config;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ProviderContentLimits {

  private static final List<Entry> ENTRIES =
      List.of(
          new Entry(
              "twilio",
              "SMS",
              "body.maxLength",
              1_600L,
              "https://www.twilio.com/docs/messaging/api/message-resource",
              LocalDate.of(2026, 10, 5)));

  private ProviderContentLimits() {}

  public record Entry(
      String providerId,
      String channel,
      String limitKey,
      long value,
      String sourceUrl,
      LocalDate consultedOn) {}

  public static List<Entry> entries() {
    return ENTRIES;
  }

  public static Map<String, Map<String, Map<String, Long>>> byProvider() {
    final Map<String, Map<String, Map<String, Long>>> table = new HashMap<>();
    for (final Entry entry : ENTRIES) {
      table
          .computeIfAbsent(entry.providerId(), provider -> new HashMap<>())
          .computeIfAbsent(entry.channel(), channel -> new HashMap<>())
          .put(entry.limitKey(), entry.value());
    }
    return table;
  }
}
