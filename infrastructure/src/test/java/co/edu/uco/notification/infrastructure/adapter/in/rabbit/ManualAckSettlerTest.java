package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

class ManualAckSettlerTest {

  private static final String ATTEMPT_HEADER = "x-dispatch-attempt";
  private static final long TAG = 11L;

  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final MessageRecoverer recoverer = mock(MessageRecoverer.class);
  private final Channel channel = mock(Channel.class);
  private final ManualAckSettler settler =
      new ManualAckSettler(
          rabbitTemplate, recoverer, "ex", "rk", ATTEMPT_HEADER, 3, "Notification dispatch");

  @BeforeEach
  void runPublicationsInsideInvoke() {
    when(rabbitTemplate.invoke(any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<RabbitOperations.OperationsCallback<?>>getArgument(0)
                    .doInRabbit(rabbitTemplate));
  }

  private static Message message(final Integer attempt, final String messageId) {
    final MessageProperties properties = new MessageProperties();
    if (messageId != null) {
      properties.setMessageId(messageId);
    }
    if (attempt != null) {
      properties.setHeader(ATTEMPT_HEADER, attempt);
    }
    return new Message("notification-1".getBytes(StandardCharsets.UTF_8), properties);
  }

  @Test
  void acknowledgeConfirmsTheDeliveryOnce() throws IOException {
    settler.acknowledge(channel, TAG);

    verify(channel).basicAck(TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void aFailureBelowTheLimitRepublishesWithTheAttemptIncrementedAndAcknowledgesTheOriginal()
      throws IOException {
    final Message original = message(null, "m-1");

    settler.handleFailure(original, channel, TAG, new IllegalStateException("boom"));

    final ArgumentCaptor<Message> republished = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq("ex"), eq("rk"), republished.capture());
    assertEquals(1, republished.getValue().getMessageProperties().getHeaders().get(ATTEMPT_HEADER));
    assertEquals("notification-1", new String(republished.getValue().getBody()));
    verify(rabbitTemplate).waitForConfirmsOrDie(anyLong());
    verify(channel).basicAck(TAG, false);
    verify(recoverer, never()).recover(any(), any());
  }

  @Test
  void theAttemptHeaderAlreadyPresentIsIncrementedFromItsCurrentValue() throws IOException {
    final Message original = message(1, "m-2");

    settler.handleFailure(original, channel, TAG, new IllegalStateException("boom"));

    final ArgumentCaptor<Message> republished = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq("ex"), eq("rk"), republished.capture());
    assertEquals(2, republished.getValue().getMessageProperties().getHeaders().get(ATTEMPT_HEADER));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void exhaustingTheAttemptsSendsTheMessageToTheDeadLetterQueueWithTheCause() throws IOException {
    final Message original = message(2, "m-3");
    final IllegalStateException cause = new IllegalStateException("boom");

    settler.handleFailure(original, channel, TAG, cause);

    verify(recoverer).recover(original, cause);
    verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    verify(rabbitTemplate).waitForConfirmsOrDie(anyLong());
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void aPublicationThatIsNotConfirmedRejectsTheMessageWithoutRequeueAndNeverAcknowledges()
      throws IOException {
    doThrow(new AmqpException("publisher nack"))
        .when(rabbitTemplate)
        .waitForConfirmsOrDie(anyLong());

    settler.handleFailure(message(null, "m-4"), channel, TAG, new IllegalStateException("boom"));

    verify(channel).basicNack(TAG, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void aDeadLetterPublicationThatIsNotConfirmedAlsoRejectsTheMessage() throws IOException {
    doThrow(new AmqpException("publisher nack"))
        .when(rabbitTemplate)
        .waitForConfirmsOrDie(anyLong());

    settler.deadLetter(message(2, "m-5"), channel, TAG, new IllegalStateException("boom"));

    verify(channel).basicNack(TAG, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void aMessageWithoutMessageIdIsSettledLikeAnyOther() throws IOException {
    final Message withoutId = message(null, null);
    assertNull(withoutId.getMessageProperties().getMessageId());

    settler.handleFailure(withoutId, channel, TAG, new IllegalStateException("boom"));

    verify(rabbitTemplate).send(eq("ex"), eq("rk"), any(Message.class));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void deadLetterRoutesTheMessageStraightToTheRecovererRegardlessOfTheAttempt() throws IOException {
    final Message original = message(null, "m-6");
    final IllegalArgumentException cause = new IllegalArgumentException("unreadable");

    settler.deadLetter(original, channel, TAG, cause);

    verify(recoverer).recover(original, cause);
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void isLastAttemptIsTrueOnlyWhenTheNextFailureWouldExhaustTheAttempts() {
    assertFalse(settler.isLastAttempt(message(null, "a")));
    assertFalse(settler.isLastAttempt(message(1, "b")));
    assertTrue(settler.isLastAttempt(message(2, "c")));
    assertTrue(settler.isLastAttempt(message(5, "d")));
  }

  @Test
  void aMaximumOfOneAttemptSendsTheFirstFailureStraightToTheDeadLetterQueue() throws IOException {
    final ManualAckSettler single =
        new ManualAckSettler(rabbitTemplate, recoverer, "ex", "rk", ATTEMPT_HEADER, 1, "x");
    final Message original = message(null, "m-7");
    final IllegalStateException cause = new IllegalStateException("boom");

    single.handleFailure(original, channel, TAG, cause);

    verify(recoverer).recover(original, cause);
    verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
  }
}
