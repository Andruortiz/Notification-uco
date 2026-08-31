package co.edu.uco.notification.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.ChannelType;
import org.junit.jupiter.api.Test;

class ChannelNotAvailableExceptionTest {

  @Test
  void messageIncludesChannelType() {
    final ChannelNotAvailableException exception =
        new ChannelNotAvailableException(ChannelType.of("EMAIL"));

    assertEquals("Channel not available: EMAIL", exception.getMessage());
  }
}
