package co.edu.uco.notification.core.usecase;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;
  private final boolean previousUseParentHandlers;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler handler =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  LogCapture(final Class<?> source) {
    this.logger = Logger.getLogger(source.getName());
    this.previousLevel = logger.getLevel();
    this.previousUseParentHandlers = logger.getUseParentHandlers();
    logger.setLevel(Level.ALL);
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);
  }

  List<LogRecord> records() {
    return List.copyOf(records);
  }

  List<LogRecord> records(final Level level, final String marker) {
    return records.stream()
        .filter(record -> record.getLevel().equals(level))
        .filter(record -> record.getMessage().contains(marker))
        .toList();
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
    logger.setLevel(previousLevel);
    logger.setUseParentHandlers(previousUseParentHandlers);
  }
}
