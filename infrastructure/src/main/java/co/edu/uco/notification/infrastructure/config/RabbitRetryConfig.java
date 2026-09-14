package co.edu.uco.notification.infrastructure.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.StatefulRetryOperationsInterceptor;

@Configuration
public class RabbitRetryConfig {

  @Bean
  MessageRecoverer notificationDispatchDlqRecoverer(
      final RabbitTemplate rabbitTemplate, final RabbitTopologyProperties properties) {
    return new RepublishMessageRecoverer(
        rabbitTemplate, properties.dlq().exchange(), properties.dlq().routingKey());
  }

  @Bean
  StatefulRetryOperationsInterceptor notificationDispatchRetryInterceptor(
      @Value("${notification.rabbit.dispatch.max-attempts:3}") final int maxAttempts,
      final MessageRecoverer notificationDispatchDlqRecoverer) {
    return RetryInterceptorBuilder.stateful()
        .maxAttempts(maxAttempts)
        .recoverer(notificationDispatchDlqRecoverer)
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      final SimpleRabbitListenerContainerFactoryConfigurer configurer,
      final ConnectionFactory connectionFactory,
      final StatefulRetryOperationsInterceptor notificationDispatchRetryInterceptor) {
    final SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    factory.setAdviceChain(notificationDispatchRetryInterceptor);
    return factory;
  }
}
