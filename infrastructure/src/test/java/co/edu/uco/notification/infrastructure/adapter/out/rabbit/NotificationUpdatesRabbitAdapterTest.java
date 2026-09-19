package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import co.edu.uco.notification.core.domain.event.NotificationAccepted;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
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

  @Autowired private NotificationEventPublisherPort eventPublisherPort;

  @Autowired private NotificationUpdatesRabbitAdapter adapter;

  private void publishAcceptedEvent(final NotificationId notificationId) {
    eventPublisherPort
        .publish(List.of(new NotificationAccepted(notificationId, Instant.now())))
        .block();
  }

  @Test
  void emitsNotificationIdWhenARealDomainEventIsPublishedToTheFanoutExchange() {
    final NotificationId notificationId = NotificationId.newId();

    StepVerifier.create(adapter.updates())
        .then(() -> publishAcceptedEvent(notificationId))
        .expectNext(notificationId)
        .thenCancel()
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void keepsEmittingToNewSubscribersAfterThePreviousSubscriberCancels() {
    final NotificationId first = NotificationId.newId();
    final NotificationId second = NotificationId.newId();

    StepVerifier.create(adapter.updates())
        .then(() -> publishAcceptedEvent(first))
        .expectNext(first)
        .thenCancel()
        .verify(Duration.ofSeconds(10));

    StepVerifier.create(adapter.updates())
        .then(() -> publishAcceptedEvent(second))
        .expectNext(second)
        .thenCancel()
        .verify(Duration.ofSeconds(10));
  }
}
