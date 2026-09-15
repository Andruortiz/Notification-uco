package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

@SpringBootTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class NotificationUpdatesRabbitAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties topologyProperties;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private NotificationUpdatesRabbitAdapter adapter;

  @Test
  void emitsNotificationIdWhenAnEventIsPublishedToTheFanoutExchange() throws Exception {
    final NotificationId notificationId = NotificationId.newId();
    final String payload =
        objectMapper.writeValueAsString(
            Map.of("notificationId", notificationId.value(), "occurredOn", Instant.now()));

    StepVerifier.create(adapter.updates())
        .then(() -> rabbitTemplate.convertAndSend(topologyProperties.eventsExchange(), "", payload))
        .expectNext(notificationId)
        .thenCancel()
        .verify(Duration.ofSeconds(10));
  }
}
