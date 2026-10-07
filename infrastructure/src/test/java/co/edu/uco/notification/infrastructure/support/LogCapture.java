package co.edu.uco.notification.infrastructure.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class LogCapture implements AutoCloseable {

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger root;

  private LogCapture() {
    final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    appender.setContext(context);
    appender.start();
    root.addAppender(appender);
  }

  public static LogCapture start() {
    return new LogCapture();
  }

  public List<ILoggingEvent> events() {
    synchronized (appender) {
      return new ArrayList<>(appender.list);
    }
  }

  public List<ILoggingEvent> withCorrelation(final String correlationId) {
    return events().stream()
        .filter(event -> correlationId.equals(event.getMDCPropertyMap().get("correlationId")))
        .toList();
  }

  @Override
  public void close() {
    root.detachAppender(appender);
    appender.stop();
  }
}
