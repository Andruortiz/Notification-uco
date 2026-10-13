package co.edu.uco.notification.infrastructure.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitRetryConfig {

  @Bean
  MessageRecoverer notificationDispatchDlqRecoverer(
      final RabbitTemplate rabbitTemplate, final RabbitTopologyProperties properties) {
    return new RepublishMessageRecoverer(
        rabbitTemplate, properties.dlq().exchange(), properties.dlq().routingKey());
  }

  @Bean
  SimpleRabbitListenerContainerFactory notificationDispatchListenerContainerFactory(
      final SimpleRabbitListenerContainerFactoryConfigurer configurer,
      final ConnectionFactory connectionFactory) {
    final SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    return factory;
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      final SimpleRabbitListenerContainerFactoryConfigurer configurer,
      final ConnectionFactory connectionFactory) {
    final SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    factory.setDefaultRequeueRejected(false);
    return factory;
  }
}
