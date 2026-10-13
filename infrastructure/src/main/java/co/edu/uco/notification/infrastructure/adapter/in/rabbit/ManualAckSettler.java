package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

final class ManualAckSettler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ManualAckSettler.class);
  private static final long CONFIRM_TIMEOUT_MILLIS = 5_000L;

  private final RabbitTemplate rabbitTemplate;
  private final MessageRecoverer deadLetterRecoverer;
  private final String exchange;
  private final String routingKey;
  private final String attemptHeader;
  private final int maxAttempts;
  private final String label;

  ManualAckSettler(
      final RabbitTemplate rabbitTemplate,
      final MessageRecoverer deadLetterRecoverer,
      final String exchange,
      final String routingKey,
      final String attemptHeader,
      final int maxAttempts,
      final String label) {
    this.rabbitTemplate =
        Preconditions.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
    this.deadLetterRecoverer =
        Preconditions.requireNonNull(deadLetterRecoverer, "deadLetterRecoverer must not be null");
    this.exchange = Preconditions.requireNonNull(exchange, "exchange must not be null");
    this.routingKey = Preconditions.requireNonNull(routingKey, "routingKey must not be null");
    this.attemptHeader =
        Preconditions.requireNonNull(attemptHeader, "attemptHeader must not be null");
    this.maxAttempts = Math.max(1, maxAttempts);
    this.label = Preconditions.requireNonNull(label, "label must not be null");
  }

  void acknowledge(final Channel channel, final long deliveryTag) throws IOException {
    channel.basicAck(deliveryTag, false);
  }

  boolean isLastAttempt(final Message message) {
    return attemptCount(message) + 1 >= maxAttempts;
  }

  void deadLetter(
      final Message message, final Channel channel, final long deliveryTag, final Throwable cause)
      throws IOException {
    settle(message, channel, deliveryTag, () -> deadLetterRecoverer.recover(message, cause));
  }

  void handleFailure(
      final Message message, final Channel channel, final long deliveryTag, final Throwable cause)
      throws IOException {
    final int attempt = attemptCount(message) + 1;
    if (attempt >= maxAttempts) {
      LOGGER.warn(
          LogFields.failure(ErrorCode.MESSAGE_ATTEMPTS_EXHAUSTED, "attempt", attempt),
          label + " exhausted attempts, sending the message to the dead-letter queue",
          cause);
      deadLetter(message, channel, deliveryTag, cause);
    } else {
      LOGGER.warn(
          LogFields.failure(
              ErrorCode.MESSAGE_ATTEMPT_FAILED, "attempt", attempt, "maxAttempts", maxAttempts),
          label + " attempt failed, requeueing",
          cause);
      settle(
          message,
          channel,
          deliveryTag,
          () -> rabbitTemplate.send(exchange, routingKey, withAttempt(message, attempt)));
    }
  }

  private void settle(
      final Message message,
      final Channel channel,
      final long deliveryTag,
      final Runnable publication)
      throws IOException {
    try {
      rabbitTemplate.invoke(
          operations -> {
            publication.run();
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
            return Boolean.TRUE;
          });
    } catch (final RuntimeException publicationFailure) {
      LOGGER.error(
          LogFields.failure(ErrorCode.MESSAGE_REPUBLISH_FAILED),
          label + " message could not be republished, rejecting it so the broker dead-letters it",
          publicationFailure);
      channel.basicNack(deliveryTag, false, false);
      return;
    }
    channel.basicAck(deliveryTag, false);
  }

  private int attemptCount(final Message message) {
    final Object value = message.getMessageProperties().getHeaders().get(attemptHeader);
    return value instanceof Integer count ? count : 0;
  }

  private Message withAttempt(final Message message, final int attempt) {
    return MessageBuilder.fromMessage(message).setHeader(attemptHeader, attempt).build();
  }
}
