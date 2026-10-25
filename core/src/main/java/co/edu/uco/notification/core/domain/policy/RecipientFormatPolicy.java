package co.edu.uco.notification.core.domain.policy;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.exception.InvalidRecipientException;
import co.edu.uco.notification.utils.EmailAddresses;
import co.edu.uco.notification.utils.PhoneNumbers;
import co.edu.uco.notification.utils.Preconditions;

public final class RecipientFormatPolicy {

  private static final String EMAIL = "EMAIL";
  private static final String SMS = "SMS";

  private RecipientFormatPolicy() {}

  public static String normalize(final ChannelType channelType, final String address) {
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(address, "address must not be null");
    final String trimmed = address.trim();
    return EMAIL.equalsIgnoreCase(channelType.value())
        ? EmailAddresses.normalize(trimmed)
        : trimmed;
  }

  public static String validate(final ChannelType channelType, final String address) {
    final String normalized = normalize(channelType, address);
    final boolean valid;
    if (EMAIL.equalsIgnoreCase(channelType.value())) {
      valid = EmailAddresses.isValid(normalized);
    } else if (SMS.equalsIgnoreCase(channelType.value())) {
      valid = PhoneNumbers.isE164(normalized);
    } else {
      valid = true;
    }
    if (!valid) {
      throw new InvalidRecipientException(channelType);
    }
    return normalized;
  }
}
