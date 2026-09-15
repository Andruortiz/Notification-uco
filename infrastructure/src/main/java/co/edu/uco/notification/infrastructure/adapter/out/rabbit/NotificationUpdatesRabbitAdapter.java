package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.port.out.NotificationUpdatesPort;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class NotificationUpdatesRabbitAdapter implements NotificationUpdatesPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(NotificationUpdatesRabbitAdapter.class);

  private final ObjectMapper objectMapper;
  private final Sinks.Many<NotificationId> sink = Sinks.many().multicast().onBackpressureBuffer();

  public NotificationUpdatesRabbitAdapter(final ObjectMapper objectMapper) {
    this.objectMapper = Preconditions.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  @RabbitListener(queues = "#{@notificationUpdatesQueue.name}")
  public void onMessage(final String payload) {
    try {
      final DomainEventEnvelope envelope =
          objectMapper.readValue(payload, DomainEventEnvelope.class);
      final NotificationId notificationId = NotificationId.of(envelope.notificationId());
      sink.tryEmitNext(notificationId);
      LOGGER.debug("Received notification update event for notificationId={}", notificationId);
    } catch (final RuntimeException | IOException e) {
      LOGGER.warn("Discarding unparseable notification update event", e);
    }
  }

  @Override
  public Flux<NotificationId> updates() {
    return sink.asFlux();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record DomainEventEnvelope(String notificationId) {}
}
