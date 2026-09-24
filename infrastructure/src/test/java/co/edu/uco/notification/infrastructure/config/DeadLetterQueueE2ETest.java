package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "notification.rabbit.dispatch.exchange=notification.dispatch.exchange.dlq-e2e",
      "notification.rabbit.dispatch.queue=notification.dispatch.queue.dlq-e2e",
      "notification.rabbit.dispatch.routing-key=notification.dispatch.dlq-e2e",
      "notification.rabbit.dlq.exchange=notification.dispatch.dlq.exchange.dlq-e2e",
      "notification.rabbit.dlq.queue=notification.dispatch.dlq.queue.dlq-e2e",
      "notification.rabbit.dlq.routing-key=notification.dispatch.dlq.dlq-e2e"
    })
@DirtiesContext
class DeadLetterQueueE2ETest {

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties properties;

  @MockBean private DispatchNotificationUseCase dispatchNotificationUseCase;

  @Test
  void aMessageThatFailsProcessingRepeatedlyEndsUpInTheDeadLetterQueue() {
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

    assertNotNull(deadLettered, "el mensaje envenenado debe llegar a la cola de mensajes muertos");
    assertEquals(poisonId.value(), new String(deadLettered.getBody(), StandardCharsets.UTF_8));
    final Object exceptionMessage =
        deadLettered
            .getMessageProperties()
            .getHeaders()
            .get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE);
    assertNotNull(exceptionMessage, "el header de causa debe estar presente");
    assertTrue(exceptionMessage.toString().contains(poisonId.value()));
  }

  private Message awaitDeadLetteredMessage() {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
    Message received = null;
    while (received == null && Instant.now().isBefore(deadline)) {
      received = rabbitTemplate.receive(properties.dlq().queue(), 500);
    }
    return received;
  }
}
