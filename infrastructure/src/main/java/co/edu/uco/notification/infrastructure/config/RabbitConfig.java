package co.edu.uco.notification.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

  @Bean
  DirectExchange notificationDispatchExchange(final RabbitTopologyProperties properties) {
    return new DirectExchange(properties.dispatch().exchange());
  }

  @Bean
  Queue notificationDispatchQueue(final RabbitTopologyProperties properties) {
    return new Queue(properties.dispatch().queue());
  }

  @Bean
  Binding notificationDispatchBinding(
      @Qualifier("notificationDispatchQueue") final Queue notificationDispatchQueue,
      @Qualifier("notificationDispatchExchange") final DirectExchange notificationDispatchExchange,
      final RabbitTopologyProperties properties) {
    return BindingBuilder.bind(notificationDispatchQueue)
        .to(notificationDispatchExchange)
        .with(properties.dispatch().routingKey());
  }

  @Bean
  FanoutExchange notificationEventsExchange(final RabbitTopologyProperties properties) {
    return new FanoutExchange(properties.eventsExchange());
  }

  @Bean
  DirectExchange notificationDispatchDlqExchange(final RabbitTopologyProperties properties) {
    return new DirectExchange(properties.dlq().exchange());
  }

  @Bean
  Queue notificationDispatchDlqQueue(final RabbitTopologyProperties properties) {
    return new Queue(properties.dlq().queue());
  }

  @Bean
  Binding notificationDispatchDlqBinding(
      @Qualifier("notificationDispatchDlqQueue") final Queue notificationDispatchDlqQueue,
      @Qualifier("notificationDispatchDlqExchange")
          final DirectExchange notificationDispatchDlqExchange,
      final RabbitTopologyProperties properties) {
    return BindingBuilder.bind(notificationDispatchDlqQueue)
        .to(notificationDispatchDlqExchange)
        .with(properties.dlq().routingKey());
  }
}
