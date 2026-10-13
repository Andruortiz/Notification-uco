package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;

public class ChannelNotAvailableException extends RuntimeException {

  public ChannelNotAvailableException(final ChannelType channelType) {
    super("Channel not available: " + channelType.value());
  }
}
