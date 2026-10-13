package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record ChannelType(String value) {

  public ChannelType {
    Preconditions.requireNonBlank(value, "ChannelType must not be blank");
  }

  public static ChannelType of(final String value) {
    return new ChannelType(value);
  }
}
