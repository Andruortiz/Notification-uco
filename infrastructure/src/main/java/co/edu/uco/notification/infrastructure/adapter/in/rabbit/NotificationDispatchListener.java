package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import co.edu.uco.notification.utils.TraceParent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;

@Component
public class NotificationDispatchListener {

  public static final String LEGACY_PREFIX = "legacy-";

  private final DispatchNotificationUseCase dispatchNotificationUseCase;

  public NotificationDispatchListener(
      final DispatchNotificationUseCase dispatchNotificationUseCase) {
    this.dispatchNotificationUseCase =
        Preconditions.requireNonNull(
            dispatchNotificationUseCase, "dispatchNotificationUseCase must not be null");
  }

  @RabbitListener(queues = "${notification.rabbit.dispatch.queue}")
  public void onMessage(
      final String notificationId,
      @Header(name = CorrelationId.AMQP_HEADER, required = false) final String correlationHeader,
      @Header(name = TraceParent.HEADER, required = false) final String traceParentHeader) {
    final CorrelationId fromHeader = CorrelationId.fromOrNull(correlationHeader);
    final CorrelationId correlationId =
        fromHeader != null
            ? fromHeader
            : CorrelationId.of(LEGACY_PREFIX + CorrelationId.newId().value());
    final TraceParent traceParent = TraceParent.fromOrNull(traceParentHeader);
    try (LogContext ignored =
        LogContext.open(correlationId, null, notificationId).withTraceParent(traceParent)) {
      Context context =
          Context.of(
              LogFields.CORRELATION_ID,
              correlationId.value(),
              LogFields.NOTIFICATION_ID,
              notificationId);
      if (traceParent != null) {
        context = context.put(LogFields.TRACE_PARENT, traceParent.value());
      }
      dispatchNotificationUseCase
          .dispatch(NotificationId.of(notificationId))
          .contextWrite(context)
          .block();
    }
  }
}
