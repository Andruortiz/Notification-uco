package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

class RabbitRetryConfigTest {

  private final RabbitRetryConfig config = new RabbitRetryConfig();
  private final SimpleRabbitListenerContainerFactoryConfigurer configurer =
      new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
  private final ConnectionFactory connectionFactory = new CachingConnectionFactory("localhost");

  @Test
  void theDispatchListenerFactoryUsesManualAcknowledgement() {
    final SimpleRabbitListenerContainerFactory factory =
        config.notificationDispatchListenerContainerFactory(configurer, connectionFactory);

    final AbstractMessageListenerContainer container = factory.createListenerContainer();

    assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
  }

  @Test
  void theDefaultListenerFactoryKeepsAutomaticAcknowledgementAsAControl() {
    final SimpleRabbitListenerContainerFactory factory =
        config.rabbitListenerContainerFactory(configurer, connectionFactory);

    final AbstractMessageListenerContainer container = factory.createListenerContainer();

    assertNotEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
  }

  @Test
  void theDispatchListenerFactoryCarriesNoStatefulRetryAdvice() {
    final SimpleRabbitListenerContainerFactory factory =
        config.notificationDispatchListenerContainerFactory(configurer, connectionFactory);

    assertNull(factory.getAdviceChain());
  }

  @Test
  void theDlqRecovererRepublishesToTheDeadLetterExchangeWithTheCauseHeader() {
    final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    final RabbitTopologyProperties properties =
        new RabbitTopologyProperties(
            new RabbitTopologyProperties.Dispatch("dex", "drk", "dq"),
            new RabbitTopologyProperties.Dlq("lex", "lrk", "lq"),
            "events");

    final MessageRecoverer recoverer =
        config.notificationDispatchDlqRecoverer(rabbitTemplate, properties);
    assertInstanceOf(RepublishMessageRecoverer.class, recoverer);
    final MessageProperties messageProperties = new MessageProperties();
    final Message message = new Message("n-1".getBytes(StandardCharsets.UTF_8), messageProperties);

    recoverer.recover(message, new IllegalStateException("provider down"));

    final ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq("lex"), eq("lrk"), sent.capture());
    assertEquals(
        "provider down",
        sent.getValue()
            .getMessageProperties()
            .getHeaders()
            .get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE));
    verify(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class));
  }
}
