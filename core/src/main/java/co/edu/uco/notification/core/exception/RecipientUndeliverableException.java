package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;

public class RecipientUndeliverableException extends RuntimeException {

  public RecipientUndeliverableException(final ChannelType channelType) {
    super(
        "RECIPIENT_UNDELIVERABLE: address is marked as undeliverable on channel "
            + channelType.value());
  }
}
