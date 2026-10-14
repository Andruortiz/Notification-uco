package co.edu.uco.notification.infrastructure.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConcurrentListAppender extends ListAppender<ILoggingEvent> {

  public ConcurrentListAppender() {
    list = new CopyOnWriteArrayList<>();
  }
}
