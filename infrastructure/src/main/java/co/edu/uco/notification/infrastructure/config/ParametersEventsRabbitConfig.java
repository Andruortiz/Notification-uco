package co.edu.uco.notification.infrastructure.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("!'${notification.parameters.events.exchange:}'.trim().isEmpty()")
public class ParametersEventsRabbitConfig {

  @Bean
  Exchange parametersEventsExchange(final ParametersEventsProperties properties) {
    return new ExchangeBuilder(properties.exchange(), properties.exchangeType())
        .durable(true)
        .build();
  }

  @Bean
  Queue parametersEventsQueue(final ParametersEventsProperties properties) {
    return QueueBuilder.durable(properties.queue())
        .deadLetterExchange(properties.dlqExchange())
        .deadLetterRoutingKey(properties.dlqRoutingKey())
        .build();
  }

  @Bean
  Binding parametersEventsBinding(
      @Qualifier("parametersEventsQueue") final Queue parametersEventsQueue,
      @Qualifier("parametersEventsExchange") final Exchange parametersEventsExchange,
      final ParametersEventsProperties properties) {
    return BindingBuilder.bind(parametersEventsQueue)
        .to(parametersEventsExchange)
        .with(properties.routingKey())
        .noargs();
  }

  @Bean
  DirectExchange parametersEventsDlqExchange(final ParametersEventsProperties properties) {
    return new DirectExchange(properties.dlqExchange());
  }

  @Bean
  Queue parametersEventsDlqQueue(final ParametersEventsProperties properties) {
    return new Queue(properties.dlqQueue());
  }

  @Bean
  Binding parametersEventsDlqBinding(
      @Qualifier("parametersEventsDlqQueue") final Queue parametersEventsDlqQueue,
      @Qualifier("parametersEventsDlqExchange") final DirectExchange parametersEventsDlqExchange,
      final ParametersEventsProperties properties) {
    return BindingBuilder.bind(parametersEventsDlqQueue)
        .to(parametersEventsDlqExchange)
        .with(properties.dlqRoutingKey());
  }

  @Bean
  MessageRecoverer parametersEventsDlqRecoverer(
      final RabbitTemplate rabbitTemplate, final ParametersEventsProperties properties) {
    return new RepublishMessageRecoverer(
        rabbitTemplate, properties.dlqExchange(), properties.dlqRoutingKey());
  }

  @Bean
  SimpleRabbitListenerContainerFactory parametersEventsListenerContainerFactory(
      final SimpleRabbitListenerContainerFactoryConfigurer configurer,
      final ConnectionFactory connectionFactory,
      final ParametersEventsProperties properties) {
    final SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    factory.setConcurrentConsumers(properties.consumerConcurrency());
    factory.setMaxConcurrentConsumers(properties.consumerConcurrency());
    factory.setPrefetchCount(1);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    return factory;
  }
}
