package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.port.in.ReceivePublishedConfigurationUseCase;
import co.edu.uco.notification.infrastructure.adapter.in.scheduler.ConfigurationEventLogger;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.infrastructure.config.ParametersEventsProperties;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;

@Component
@ConditionalOnExpression("!'${notification.parameters.events.exchange:}'.trim().isEmpty()")
public class ParametersEventListener {

  static final String ATTEMPT_HEADER = "x-parameters-event-attempt";

  private static final Logger LOGGER = LoggerFactory.getLogger(ParametersEventListener.class);

  private final ReceivePublishedConfigurationUseCase receiveUseCase;
  private final ConfigurationEventLogger eventLogger;
  private final ObjectMapper objectMapper;
  private final ManualAckSettler settler;

  public ParametersEventListener(
      final ReceivePublishedConfigurationUseCase receiveUseCase,
      final ConfigurationEventLogger eventLogger,
      final ObjectMapper objectMapper,
      final RabbitTemplate rabbitTemplate,
      @Qualifier("parametersEventsDlqRecoverer")
          final MessageRecoverer parametersEventsDlqRecoverer,
      final ParametersEventsProperties properties) {
    this.receiveUseCase =
        Preconditions.requireNonNull(receiveUseCase, "receiveUseCase must not be null");
    this.eventLogger = Preconditions.requireNonNull(eventLogger, "eventLogger must not be null");
    this.objectMapper = Preconditions.requireNonNull(objectMapper, "objectMapper must not be null");
    Preconditions.requireNonNull(properties, "properties must not be null");
    this.settler =
        new ManualAckSettler(
            rabbitTemplate,
            parametersEventsDlqRecoverer,
            "",
            properties.queue(),
            ATTEMPT_HEADER,
            properties.maxAttempts(),
            "Parameters event");
  }

  @RabbitListener(
      queues = "${notification.parameters.events.queue}",
      containerFactory = "parametersEventsListenerContainerFactory")
  public void onMessage(
      final Message message,
      final Channel channel,
      @Header(AmqpHeaders.DELIVERY_TAG) final long deliveryTag)
      throws IOException {
    final String correlationId = ConfigurationEventLogger.newCorrelationId();
    try (LogContext ignored = LogContext.open(CorrelationId.of(correlationId), null, null)) {
      process(message, channel, deliveryTag, correlationId);
    }
  }

  private void process(
      final Message message,
      final Channel channel,
      final long deliveryTag,
      final String correlationId)
      throws IOException {
    final ConfigurationChange change;
    try {
      change = objectMapper.readValue(message.getBody(), ParametersEventPayload.class).toChange();
    } catch (final RuntimeException | IOException cause) {
      LOGGER.warn(
          LogFields.failure(
              ErrorCode.PARAMETERS_EVENT_UNREADABLE, "errorType", cause.getClass().getSimpleName()),
          "Parameters event is unreadable, sending it to the dead-letter queue");
      settler.deadLetter(message, channel, deliveryTag, cause);
      return;
    }
    final ConfigurationChangeOutcome outcome;
    try {
      outcome =
          receiveUseCase
              .receive(change)
              .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, correlationId))
              .block();
    } catch (final RuntimeException cause) {
      settler.handleFailure(message, channel, deliveryTag, cause);
      return;
    }
    if (outcome != null) {
      eventLogger.logOutcome(outcome, correlationId, ConfigurationEventLogger.TRANSPORT_EVENT);
    }
    settler.acknowledge(channel, deliveryTag);
  }
}
