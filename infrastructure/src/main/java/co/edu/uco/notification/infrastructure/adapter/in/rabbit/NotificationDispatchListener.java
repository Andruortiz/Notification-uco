package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.DispatchResultNotPersistedException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.FailureCategory;
import co.edu.uco.notification.utils.Preconditions;
import co.edu.uco.notification.utils.TraceParent;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;

@Component
public class NotificationDispatchListener {

  public static final String LEGACY_PREFIX = "legacy-";
  static final String ATTEMPT_HEADER = "x-dispatch-attempt";

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDispatchListener.class);

  private final DispatchNotificationUseCase dispatchNotificationUseCase;
  private final ManualAckSettler settler;

  public NotificationDispatchListener(
      final DispatchNotificationUseCase dispatchNotificationUseCase,
      final RabbitTemplate rabbitTemplate,
      @Qualifier("notificationDispatchDlqRecoverer") final MessageRecoverer dlqRecoverer,
      final RabbitTopologyProperties properties,
      @Value("${notification.rabbit.dispatch.max-attempts:3}") final int maxAttempts) {
    this.dispatchNotificationUseCase =
        Preconditions.requireNonNull(
            dispatchNotificationUseCase, "dispatchNotificationUseCase must not be null");
    Preconditions.requireNonNull(properties, "properties must not be null");
    this.settler =
        new ManualAckSettler(
            rabbitTemplate,
            dlqRecoverer,
            properties.dispatch().exchange(),
            properties.dispatch().routingKey(),
            ATTEMPT_HEADER,
            maxAttempts,
            "Notification dispatch");
  }

  @RabbitListener(
      queues = "${notification.rabbit.dispatch.queue}",
      containerFactory = "notificationDispatchListenerContainerFactory")
  public void onMessage(
      final Message message,
      final Channel channel,
      @Header(AmqpHeaders.DELIVERY_TAG) final long deliveryTag)
      throws IOException {
    final CorrelationId fromHeader = CorrelationContext.fromHeaders(message.getMessageProperties());
    final CorrelationId correlationId =
        fromHeader != null
            ? fromHeader
            : CorrelationId.of(LEGACY_PREFIX + CorrelationId.newId().value());
    final TraceParent traceParent =
        CorrelationContext.traceFromHeaders(message.getMessageProperties());
    final String body = new String(message.getBody(), StandardCharsets.UTF_8);
    try (LogContext ignored =
        LogContext.open(correlationId, null, body).withTraceParent(traceParent)) {
      process(message, channel, deliveryTag, body, correlationId, traceParent);
    }
  }

  private void process(
      final Message message,
      final Channel channel,
      final long deliveryTag,
      final String body,
      final CorrelationId correlationId,
      final TraceParent traceParent)
      throws IOException {
    final NotificationId notificationId;
    try {
      notificationId = NotificationId.of(body);
    } catch (final RuntimeException cause) {
      LOGGER.warn(
          LogFields.fields(LogFields.FAILURE_CATEGORY, FailureCategory.PERMANENT_BUSINESS),
          "Dispatch message has no notification id, sending it to the dead-letter queue",
          cause);
      settler.deadLetter(message, channel, deliveryTag, cause);
      return;
    }
    Context context =
        Context.of(
            LogFields.CORRELATION_ID,
            correlationId.value(),
            LogFields.NOTIFICATION_ID,
            notificationId.value());
    if (traceParent != null) {
      context = context.put(LogFields.TRACE_PARENT, traceParent.value());
    }
    RuntimeException failure = null;
    try {
      dispatchNotificationUseCase.dispatch(notificationId).contextWrite(context).block();
    } catch (final RuntimeException cause) {
      failure = cause;
    }
    if (failure == null) {
      settler.acknowledge(channel, deliveryTag);
    } else if (failure instanceof DispatchResultNotPersistedException) {
      LOGGER.error(
          LogFields.fields(LogFields.FAILURE_CATEGORY, FailureCategory.RECOVERABLE_INFRASTRUCTURE),
          "Dispatch result could not be persisted, sending the message to the dead-letter queue",
          failure);
      settler.deadLetter(message, channel, deliveryTag, failure);
    } else {
      settler.handleFailure(message, channel, deliveryTag, failure);
    }
  }
}
