package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.event.NotificationDiscarded;
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.event.NotificationRecoverable;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.FailureCategory;
import co.edu.uco.notification.utils.Preconditions;
import co.edu.uco.notification.utils.TraceParent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@EnableConfigurationProperties(RabbitTopologyProperties.class)
public class NotificationRabbitPublisher implements NotificationEventPublisherPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationRabbitPublisher.class);

  private static final String LIFECYCLE_MESSAGE = "Notification lifecycle event";

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final RabbitTopologyProperties properties;

  public NotificationRabbitPublisher(
      final RabbitTemplate rabbitTemplate,
      final ObjectMapper objectMapper,
      final RabbitTopologyProperties properties) {
    this.rabbitTemplate =
        Preconditions.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
    this.objectMapper = Preconditions.requireNonNull(objectMapper, "objectMapper must not be null");
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
  }

  @Override
  public Mono<Void> enqueueForDispatch(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    return Mono.deferContextual(
        context -> {
          final CorrelationId correlationId =
              notification.correlationId() != null
                  ? notification.correlationId()
                  : CorrelationContext.from(context);
          final TraceParent traceParent = CorrelationContext.traceFrom(context);
          return Mono.<Void>fromRunnable(
                  () ->
                      rabbitTemplate.convertAndSend(
                          properties.dispatch().exchange(),
                          properties.dispatch().routingKey(),
                          notification.notificationId().value(),
                          message -> {
                            message
                                .getMessageProperties()
                                .setMessageId(notification.notificationId().value());
                            CorrelationContext.stamp(
                                message.getMessageProperties(), correlationId, traceParent);
                            return message;
                          }))
              .subscribeOn(Schedulers.boundedElastic());
        });
  }

  @Override
  public Mono<Void> publish(final List<DomainEvent> events) {
    Preconditions.requireNonNull(events, "events must not be null");
    return Mono.deferContextual(
        context -> {
          final CorrelationId correlationId = CorrelationContext.from(context);
          final TraceParent traceParent = CorrelationContext.traceFrom(context);
          return Mono.<Void>fromRunnable(
                  () -> events.forEach(event -> publishEvent(event, correlationId, traceParent)))
              .subscribeOn(Schedulers.boundedElastic());
        });
  }

  private void publishEvent(
      final DomainEvent event,
      final CorrelationId contextCorrelationId,
      final TraceParent traceParent) {
    final CorrelationId correlationId =
        event.correlationId() != null ? event.correlationId() : contextCorrelationId;
    try {
      rabbitTemplate.convertAndSend(
          properties.eventsExchange(),
          "",
          objectMapper.writeValueAsString(event),
          message -> {
            CorrelationContext.stamp(message.getMessageProperties(), correlationId, traceParent);
            return message;
          });
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException(
          "Failed to serialize domain event " + event.getClass().getSimpleName(), e);
    }
    logLifecycle(event);
  }

  private static void logLifecycle(final DomainEvent event) {
    try (LogContext ignored = LogContext.of(event)) {
      final String eventType = event.getClass().getSimpleName();
      if (event instanceof NotificationFailed || event instanceof NotificationDiscarded) {
        LOGGER.error(
            LogFields.fields(
                "event", eventType, LogFields.FAILURE_CATEGORY, FailureCategory.PERMANENT_BUSINESS),
            LIFECYCLE_MESSAGE);
      } else if (event instanceof NotificationRecoverable) {
        LOGGER.warn(
            LogFields.fields(
                "event",
                eventType,
                LogFields.FAILURE_CATEGORY,
                FailureCategory.RECOVERABLE_PROVIDER),
            LIFECYCLE_MESSAGE);
      } else {
        LOGGER.info(LogFields.fields("event", eventType), LIFECYCLE_MESSAGE);
      }
    }
  }
}
