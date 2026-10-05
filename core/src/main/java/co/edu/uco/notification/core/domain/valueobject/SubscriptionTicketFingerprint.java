package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class SubscriptionTicketFingerprint {

  private SubscriptionTicketFingerprint() {}

  public static String of(final String ticket) {
    Preconditions.requireNonBlank(ticket, "ticket must not be blank");
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
