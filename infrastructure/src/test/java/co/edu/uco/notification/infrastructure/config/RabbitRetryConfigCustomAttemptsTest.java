package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "notification.rabbit.dispatch.max-attempts=1",
      "notification.rabbit.dispatch.exchange=notification.dispatch.exchange.retry-custom",
      "notification.rabbit.dispatch.queue=notification.dispatch.queue.retry-custom",
      "notification.rabbit.dispatch.routing-key=notification.dispatch.retry-custom",
      "notification.rabbit.dlq.exchange=notification.dispatch.dlq.exchange.retry-custom",
      "notification.rabbit.dlq.queue=notification.dispatch.dlq.queue.retry-custom",
      "notification.rabbit.dlq.routing-key=notification.dispatch.dlq.retry-custom"
    })
@DirtiesContext
class RabbitRetryConfigCustomAttemptsTest {

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties properties;

  @Autowired private ConfigurationView configurationView;

  @MockBean private DispatchNotificationUseCase dispatchNotificationUseCase;

  @Test
  void aSingleFailureAlreadyMovesTheMessageToTheDeadLetterQueueWhenMaxAttemptsIsOne() {
    final NotificationId poisonId = NotificationId.newId();
    when(dispatchNotificationUseCase.dispatch(any()))
        .thenReturn(Mono.error(new NotificationNotFoundException(poisonId)));

    rabbitTemplate.convertAndSend(
        properties.dispatch().exchange(),
        properties.dispatch().routingKey(),
        poisonId.value(),
        message -> {
          message.getMessageProperties().setMessageId(poisonId.value());
          return message;
        });

    final Message deadLettered = awaitDeadLetteredMessage();

    assertNotNull(
        deadLettered,
        "con max-attempts=1, un único fallo ya debe mover el mensaje a la cola de mensajes muertos");
    assertNull(deadLettered.getMessageProperties().getHeaders().get("x-dispatch-attempt"));
    verify(dispatchNotificationUseCase, times(1)).dispatch(any());
  }

  @Test
  void theMaximumResolvedPerMessageComesFromTheConfigurationViewSeededByTheProperty() {
    assertEquals(1, configurationView.snapshot().dispatchMaxAttempts());
  }

  private Message awaitDeadLetteredMessage() {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    Message received = null;
    while (received == null && Instant.now().isBefore(deadline)) {
      received = rabbitTemplate.receive(properties.dlq().queue(), 500);
    }
    return received;
  }
}
