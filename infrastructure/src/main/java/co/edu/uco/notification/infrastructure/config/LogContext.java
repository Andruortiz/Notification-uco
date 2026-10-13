package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.utils.CorrelationId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

public final class LogContext implements AutoCloseable {

  private final Map<String, String> previous = new LinkedHashMap<>();

  private LogContext() {}

  public static LogContext open(
      final CorrelationId correlationId, final String tenantId, final String notificationId) {
    final LogContext context = new LogContext();
    context.set(LogFields.CORRELATION_ID, correlationId == null ? null : correlationId.value());
    context.set(LogFields.TENANT_ID, tenantId);
    context.set(LogFields.NOTIFICATION_ID, notificationId);
    return context;
  }

  public static LogContext of(final Notification notification) {
    return open(
        notification.correlationId(),
        notification.tenantId().value(),
        notification.notificationId().value());
  }

  public static LogContext of(final DomainEvent event) {
    return open(event.correlationId(), event.tenantId().value(), event.notificationId().value());
  }

  private void set(final String key, final String value) {
    if (value == null) {
      return;
    }
    previous.put(key, MDC.get(key));
    MDC.put(key, value);
  }

  @Override
  public void close() {
    previous.forEach(
        (key, old) -> {
          if (old == null) {
            MDC.remove(key);
          } else {
            MDC.put(key, old);
          }
        });
  }
}
