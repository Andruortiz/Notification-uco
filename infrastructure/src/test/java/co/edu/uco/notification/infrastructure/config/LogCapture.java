package co.edu.uco.notification.infrastructure.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Level previousLevel;

  private LogCapture(final Logger logger) {
    this.logger = logger;
    this.previousLevel = logger.getLevel();
    logger.setLevel(Level.ALL);
    appender.start();
    logger.addAppender(appender);
  }

  public static LogCapture of(final Class<?> type) {
    return new LogCapture((Logger) LoggerFactory.getLogger(type));
  }

  public List<ILoggingEvent> events() {
    return List.copyOf(appender.list);
  }

  public String rendered() {
    final StringBuilder out = new StringBuilder();
    appender.list.forEach(
        event -> {
          out.append(event.getFormattedMessage()).append(' ');
          event.getMarkerList().forEach(marker -> out.append(marker).append(' '));
          out.append(event.getMDCPropertyMap()).append(' ');
          if (event.getThrowableProxy() != null) {
            out.append(event.getThrowableProxy().getMessage()).append(' ');
          }
        });
    return out.toString();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
    appender.stop();
  }
}
