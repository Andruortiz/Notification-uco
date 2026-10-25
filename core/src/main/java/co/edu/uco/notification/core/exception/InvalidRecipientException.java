package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;

public class InvalidRecipientException extends RuntimeException {

  public InvalidRecipientException(final ChannelType channelType) {
    super("INVALID_RECIPIENT: address does not match the format of channel " + channelType.value());
  }
}
