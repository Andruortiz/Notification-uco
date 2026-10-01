package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import co.edu.uco.notification.utils.CorrelationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class NotificationRabbitPublisherTest {

  private static final RabbitTopologyProperties PROPERTIES =
      new RabbitTopologyProperties(
          new RabbitTopologyProperties.Dispatch(
              "notification.dispatch.exchange",
              "notification.dispatch",
              "notification.dispatch.queue"),
          new RabbitTopologyProperties.Dlq(
              "notification.dispatch.dlq.exchange",
              "notification.dispatch.dlq",
              "notification.dispatch.dlq.queue"),
          "notification.events.exchange");

  private static ObjectMapper objectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }

  private static Notification aNotification() {
    return aNotification(null);
  }

  private static Notification aNotification(final CorrelationId correlationId) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        correlationId);
  }

  @Test
  void enqueueForDispatchSendsTheNotificationIdToTheDispatchExchange() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);
    final Notification notification = aNotification();

    StepVerifier.create(publisher.enqueueForDispatch(notification)).verifyComplete();

    final ArgumentCaptor<MessagePostProcessor> postProcessor =
        ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(rabbitTemplate)
        .convertAndSend(
            eq("notification.dispatch.exchange"),
            eq("notification.dispatch"),
            eq(notification.notificationId().value()),
            postProcessor.capture());

    final Message message = new Message(new byte[0], new MessageProperties());
    postProcessor.getValue().postProcessMessage(message);
    assertEquals(
        notification.notificationId().value(), message.getMessageProperties().getMessageId());
  }

  @Test
  void enqueueForDispatchStampsThePersistedCorrelationIdAsAHeader() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);
    final Notification notification = aNotification(CorrelationId.of("corr-1"));

    StepVerifier.create(publisher.enqueueForDispatch(notification)).verifyComplete();

    final ArgumentCaptor<MessagePostProcessor> postProcessor =
        ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(rabbitTemplate)
        .convertAndSend(
            any(String.class), any(String.class), any(Object.class), postProcessor.capture());
    final Message message = new Message(new byte[0], new MessageProperties());
    postProcessor.getValue().postProcessMessage(message);
    assertEquals(
        "corr-1", message.getMessageProperties().getHeaders().get(CorrelationId.AMQP_HEADER));
  }

  @Test
  void enqueueForDispatchFallsBackToTheReactorContextCorrelationId() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);

    StepVerifier.create(
            publisher
                .enqueueForDispatch(aNotification())
                .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "ctx-9")))
        .verifyComplete();

    final ArgumentCaptor<MessagePostProcessor> postProcessor =
        ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(rabbitTemplate)
        .convertAndSend(
            any(String.class), any(String.class), any(Object.class), postProcessor.capture());
    final Message message = new Message(new byte[0], new MessageProperties());
    postProcessor.getValue().postProcessMessage(message);
    assertEquals(
        "ctx-9", message.getMessageProperties().getHeaders().get(CorrelationId.AMQP_HEADER));
  }

  @Test
  void publishSendsEachEventAsJsonToTheEventsExchange() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);
    final Notification notification = aNotification();
    final List<DomainEvent> events =
        List.of(
            new NotificationAccepted(
                notification.notificationId(), notification.tenantId(), null, Instant.now()),
            new NotificationQueued(
                notification.notificationId(), notification.tenantId(), null, Instant.now()));

    StepVerifier.create(publisher.publish(events)).verifyComplete();

    verify(rabbitTemplate, times(2))
        .convertAndSend(
            eq("notification.events.exchange"),
            eq(""),
            contains(notification.notificationId().value()),
            any(MessagePostProcessor.class));
  }

  @Test
  void publishStampsEachEventWithItsOwnPersistedCorrelationIdNotTheContextOne() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);
    final Notification notification = aNotification(CorrelationId.of("persisted-1"));
    final List<DomainEvent> events = notification.pullEvents();

    StepVerifier.create(
            publisher
                .publish(events)
                .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "sched-ctx")))
        .verifyComplete();

    final ArgumentCaptor<MessagePostProcessor> postProcessor =
        ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(rabbitTemplate)
        .convertAndSend(
            any(String.class), any(String.class), any(Object.class), postProcessor.capture());
    final Message message = new Message(new byte[0], new MessageProperties());
    postProcessor.getValue().postProcessMessage(message);
    assertEquals(
        "persisted-1", message.getMessageProperties().getHeaders().get(CorrelationId.AMQP_HEADER));
  }

  @Test
  void publishWithoutPersistedIdFallsBackToTheContextCorrelationId() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final NotificationRabbitPublisher publisher =
        new NotificationRabbitPublisher(rabbitTemplate, objectMapper(), PROPERTIES);
    final List<DomainEvent> events = aNotification().pullEvents();

    StepVerifier.create(
            publisher.publish(events).contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "ctx-5")))
        .verifyComplete();

    final ArgumentCaptor<MessagePostProcessor> postProcessor =
        ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(rabbitTemplate)
        .convertAndSend(
            any(String.class), any(String.class), any(Object.class), postProcessor.capture());
    final Message message = new Message(new byte[0], new MessageProperties());
    postProcessor.getValue().postProcessMessage(message);
    assertEquals(
        "ctx-5", message.getMessageProperties().getHeaders().get(CorrelationId.AMQP_HEADER));
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
