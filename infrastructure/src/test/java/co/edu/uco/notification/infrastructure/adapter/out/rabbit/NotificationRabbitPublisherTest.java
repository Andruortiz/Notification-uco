package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.event.NotificationAccepted;
import co.edu.uco.notification.core.domain.event.NotificationQueued;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.test.StepVerifier;

class NotificationRabbitPublisherTest {

  private static final RabbitTopologyProperties PROPERTIES =
      new RabbitTopologyProperties(
          new RabbitTopologyProperties.Dispatch(
              "notification.dispatch.exchange",
              "notification.dispatch",
              "notification.dispatch.queue"),
          "notification.events.exchange");

  private static ObjectMapper objectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }

  private static Notification aNotification() {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
  }

  @Test
  void enqueueForDispatchSendsTheNotificationIdToTheDispatchExchange() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);
    final Notification notification = aNotification();

    StepVerifier.create(publisher.enqueueForDispatch(notification)).verifyComplete();

    verify(rabbitTemplate)
        .convertAndSend(
            "notification.dispatch.exchange",
            "notification.dispatch",
            notification.notificationId().value());
  }

  @Test
  void publishSendsEachEventAsJsonToTheEventsExchange() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);
    final Notification notification = aNotification();
    final List<DomainEvent> events =
        List.of(
            new NotificationAccepted(notification.notificationId(), Instant.now()),
            new NotificationQueued(notification.notificationId(), Instant.now()));

    StepVerifier.create(publisher.publish(events)).verifyComplete();

    verify(rabbitTemplate, times(2))
        .convertAndSend(
            eq("notification.events.exchange"),
            eq(""),
            contains(notification.notificationId().value()));
  }

  @Test
  void publishWithNoEventsSendsNothing() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);

    StepVerifier.create(publisher.publish(List.of())).verifyComplete();

    verifyNoInteractions(rabbitTemplate);
  }

  @Test
  void enqueueForDispatchRejectsNullNotification() {
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(mock(RabbitTemplate.class), objectMapper(), PROPERTIES);

    assertThrows(NullPointerException.class, () -> publisher.enqueueForDispatch(null));
  }

  @Test
  void publishRejectsNullEvents() {
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(mock(RabbitTemplate.class), objectMapper(), PROPERTIES);

    assertThrows(NullPointerException.class, () -> publisher.publish(null));
  }

  @Test
  void constructorRejectsNullDependencies() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final ObjectMapper objectMapper = objectMapper();

    assertThrows(
        NullPointerException.class,
        () -> new NotificationRabbitPublisher(null, objectMapper, PROPERTIES));
    assertThrows(
        NullPointerException.class,
        () -> new NotificationRabbitPublisher(rabbitTemplate, null, PROPERTIES));
    assertThrows(
        NullPointerException.class,
        () -> new NotificationRabbitPublisher(rabbitTemplate, objectMapper, null));
  }
}
