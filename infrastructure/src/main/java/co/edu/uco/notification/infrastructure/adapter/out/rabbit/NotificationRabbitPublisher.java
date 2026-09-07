package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@EnableConfigurationProperties(RabbitTopologyProperties.class)
public class NotificationRabbitPublisher implements NotificationEventPublisherPort {

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
    return Mono.<Void>fromRunnable(
            () ->
                rabbitTemplate.convertAndSend(
                    properties.dispatch().exchange(),
                    properties.dispatch().routingKey(),
                    notification.notificationId().value()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<Void> publish(final List<DomainEvent> events) {
    Preconditions.requireNonNull(events, "events must not be null");
    return Mono.<Void>fromRunnable(() -> events.forEach(this::publishEvent))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void publishEvent(final DomainEvent event) {
    try {
      rabbitTemplate.convertAndSend(
          properties.eventsExchange(), "", objectMapper.writeValueAsString(event));
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize domain event " + event, e);
    }
  }
}
