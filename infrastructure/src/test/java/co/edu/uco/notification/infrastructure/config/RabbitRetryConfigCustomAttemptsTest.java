package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
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
    properties = "notification.rabbit.dispatch.max-attempts=1")
@DirtiesContext
class RabbitRetryConfigCustomAttemptsTest {

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties properties;

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

    assertNotNull(
        awaitDeadLetteredMessage(),
        "con max-attempts=1, un único fallo ya debe mover el mensaje a la cola de mensajes muertos");
  }

  private Message awaitDeadLetteredMessage() {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    Message received = null;
    while (received == null && Instant.now().isBefore(deadline)) {
      received = rabbitTemplate.receive(properties.dlq().queue(), 500);
    }
    return received;
  }
}
