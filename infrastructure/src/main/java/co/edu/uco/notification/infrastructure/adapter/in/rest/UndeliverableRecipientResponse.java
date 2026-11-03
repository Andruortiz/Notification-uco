package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.UndeliverableMarkView;
import co.edu.uco.notification.utils.EmailAddresses;
import co.edu.uco.notification.utils.PhoneNumbers;
import java.time.Instant;

public record UndeliverableRecipientResponse(
    boolean marked, String channel, String address, String reason, Instant markedAt) {

  public static UndeliverableRecipientResponse from(
      final UndeliverableMarkView view, final boolean unmasked) {
    final String channel = view.channelType().value();
    return new UndeliverableRecipientResponse(
        view.marked(),
        channel,
        unmasked ? view.address() : mask(channel, view.address()),
        view.reason(),
        view.markedAt());
  }

  private static String mask(final String channel, final String address) {
    return "EMAIL".equalsIgnoreCase(channel)
        ? EmailAddresses.mask(address)
        : PhoneNumbers.mask(address);
  }
}
