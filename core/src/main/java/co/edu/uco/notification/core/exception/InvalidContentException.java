package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;

public class InvalidContentException extends RuntimeException {

  public InvalidContentException(final ChannelType channelType, final String details) {
    super("Content does not match the schema for channel " + channelType.value() + ": " + details);
  }
}
