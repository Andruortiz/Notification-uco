package co.edu.uco.notification.infrastructure.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import co.edu.uco.notification.utils.LogSanitizer;
import net.logstash.logback.stacktrace.ShortenedThrowableConverter;

public class SanitizingThrowableConverter extends ShortenedThrowableConverter {

  @Override
  public String convert(final ILoggingEvent event) {
    return LogSanitizer.scrub(super.convert(event));
  }
}
