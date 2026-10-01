package co.edu.uco.notification.infrastructure.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.StringJoiner;

public final class LogLines {

  private LogLines() {}

  public static String render(final ILoggingEvent event) {
    final StringJoiner line = new StringJoiner(" ");
    line.add(event.getFormattedMessage());
    if (event.getMarkerList() != null) {
      event.getMarkerList().forEach(marker -> line.add(marker.toString()));
    }
    event.getMDCPropertyMap().forEach((key, value) -> line.add(key + "=" + value));
    return line.toString();
  }
}
