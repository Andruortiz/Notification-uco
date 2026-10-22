package co.edu.uco.notification.infrastructure.adapter.out.provider;

import java.util.Map;

public final class WhatsAppFailureReasons {

  public static final String UNCLASSIFIED = "unclassified";

  private static final Map<Integer, String> BY_CODE =
      Map.ofEntries(
          Map.entry(21654, "content-sid-required"),
          Map.entry(63016, "outside-session-window"),
          Map.entry(63024, "invalid-recipient"),
          Map.entry(21211, "invalid-recipient"),
          Map.entry(63015, "recipient-not-in-sandbox"),
          Map.entry(63007, "sender-not-configured"),
          Map.entry(63112, "sender-not-configured"),
          Map.entry(20003, "invalid-credentials"));

  private WhatsAppFailureReasons() {}

  public static String reasonOf(final Integer providerErrorCode) {
    if (providerErrorCode == null) {
      return UNCLASSIFIED;
    }
    return BY_CODE.getOrDefault(providerErrorCode, UNCLASSIFIED);
  }
}
