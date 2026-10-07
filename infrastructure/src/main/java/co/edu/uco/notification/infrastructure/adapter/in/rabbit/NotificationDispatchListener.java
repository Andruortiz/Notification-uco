package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.DispatchResultNotPersistedException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.infrastructure.config.ReactorObservations;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import com.rabbitmq.client.Channel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;

@Component
public class NotificationDispatchListener {

  public static final String LEGACY_PREFIX = "legacy-";
  static final String ATTEMPT_HEADER = "x-dispatch-attempt";

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDispatchListener.class);

  private final DispatchNotificationUseCase dispatchNotificationUseCase;
  private final IntFunction<ManualAckSettler> settlerFactory;
  private final RabbitTopologyProperties properties;
  private final ConfigurationView configurationView;
  private final ObservationRegistry observationRegistry;

  public NotificationDispatchListener(
      final DispatchNotificationUseCase dispatchNotificationUseCase,
      final RabbitTemplate rabbitTemplate,
      @Qualifier("notificationDispatchDlqRecoverer") final MessageRecoverer dlqRecoverer,
      final RabbitTopologyProperties properties,
      final ConfigurationView configurationView,
      final ObservationRegistry observationRegistry) {
    this.dispatchNotificationUseCase =
        Preconditions.requireNonNull(
            dispatchNotificationUseCase, "dispatchNotificationUseCase must not be null");
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
    this.settlerFactory =
        maxAttempts ->
            new ManualAckSettler(
                rabbitTemplate,
                dlqRecoverer,
                properties.dispatch().exchange(),
                properties.dispatch().routingKey(),
                ATTEMPT_HEADER,
                maxAttempts,
                "Notification dispatch");
    this.configurationView =
        Preconditions.requireNonNull(configurationView, "configurationView must not be null");
    this.observationRegistry =
        Preconditions.requireNonNull(observationRegistry, "observationRegistry must not be null");
  }

  private ManualAckSettler settlerFor(final int maxAttempts) {
    return settlerFactory.apply(maxAttempts);
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
    final String body = new String(message.getBody(), StandardCharsets.UTF_8);
    try (LogContext ignored = LogContext.open(correlationId, null, body)) {
      process(message, channel, deliveryTag, body, correlationId);
    }
  }

  private void process(
      final Message message,
      final Channel channel,
      final long deliveryTag,
      final String body,
      final CorrelationId correlationId)
      throws IOException {
    final ManualAckSettler settler =
        settlerFor(Math.toIntExact(configurationView.snapshot().dispatchMaxAttempts()));
    final NotificationId notificationId;
    try {
      notificationId = NotificationId.of(body);
    } catch (final RuntimeException cause) {
      LOGGER.warn(
          LogFields.failure(ErrorCode.DISPATCH_MESSAGE_INVALID),
          "Dispatch message has no notification id, sending it to the dead-letter queue",
          cause);
      settler.deadLetter(message, channel, deliveryTag, cause);
      return;
    }
    final Observation dispatch = startDispatchObservation(correlationId);
    final Context context =
        ReactorObservations.with(
            Context.of(
                LogFields.CORRELATION_ID,
                correlationId.value(),
                LogFields.NOTIFICATION_ID,
                notificationId.value()),
            dispatch);
    RuntimeException failure = null;
    try (Observation.Scope ignored = dispatch.openScope()) {
      dispatchNotificationUseCase.dispatch(notificationId).contextWrite(context).block();
    } catch (final RuntimeException cause) {
      dispatch.error(cause);
      failure = cause;
    } finally {
      dispatch.stop();
    }
    if (failure == null) {
      settler.acknowledge(channel, deliveryTag);
    } else if (failure instanceof DispatchResultNotPersistedException) {
      LOGGER.error(
          LogFields.failure(ErrorCode.DISPATCH_RESULT_NOT_PERSISTED),
          "Dispatch result could not be persisted, sending the message to the dead-letter queue",
          failure);
      settler.deadLetter(message, channel, deliveryTag, failure);
    } else {
      settler.handleFailure(message, channel, deliveryTag, failure);
    }
  }

  private Observation startDispatchObservation(final CorrelationId correlationId) {
    final Observation.Context observationContext = new Observation.Context();
    observationContext.put(CorrelationId.CONTEXT_KEY, correlationId.value());
    return Observation.createNotStarted(
            "notification.dispatch", () -> observationContext, observationRegistry)
        .start();
  }
}
