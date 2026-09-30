package co.edu.uco.notification.infrastructure.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AttachmentScanTopologyProperties.class)
public class AttachmentScanRabbitConfig {

  @Bean
  DirectExchange attachmentScanExchange(final AttachmentScanTopologyProperties properties) {
    return new DirectExchange(properties.exchange());
  }

  @Bean
  Queue attachmentScanQueue(final AttachmentScanTopologyProperties properties) {
    return new Queue(properties.queue());
  }

  @Bean
  Binding attachmentScanBinding(
      @Qualifier("attachmentScanQueue") final Queue attachmentScanQueue,
      @Qualifier("attachmentScanExchange") final DirectExchange attachmentScanExchange,
      final AttachmentScanTopologyProperties properties) {
    return BindingBuilder.bind(attachmentScanQueue)
        .to(attachmentScanExchange)
        .with(properties.routingKey());
  }

  @Bean
  DirectExchange attachmentScanDlqExchange(final AttachmentScanTopologyProperties properties) {
    return new DirectExchange(properties.dlqExchange());
  }

  @Bean
  Queue attachmentScanDlqQueue(final AttachmentScanTopologyProperties properties) {
    return new Queue(properties.dlqQueue());
  }

  @Bean
  Binding attachmentScanDlqBinding(
      @Qualifier("attachmentScanDlqQueue") final Queue attachmentScanDlqQueue,
      @Qualifier("attachmentScanDlqExchange") final DirectExchange attachmentScanDlqExchange,
      final AttachmentScanTopologyProperties properties) {
    return BindingBuilder.bind(attachmentScanDlqQueue)
        .to(attachmentScanDlqExchange)
        .with(properties.dlqRoutingKey());
  }

  @Bean
  MessageRecoverer attachmentScanDlqRecoverer(
      final RabbitTemplate rabbitTemplate, final AttachmentScanTopologyProperties topology) {
    return new RepublishMessageRecoverer(
        rabbitTemplate, topology.dlqExchange(), topology.dlqRoutingKey());
  }

  @Bean
  SimpleRabbitListenerContainerFactory attachmentScanListenerContainerFactory(
      final SimpleRabbitListenerContainerFactoryConfigurer configurer,
      final ConnectionFactory connectionFactory,
      final AttachmentProperties attachmentProperties) {
    final SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    final int concurrency = Math.max(1, attachmentProperties.scan().consumerConcurrency());
    factory.setConcurrentConsumers(concurrency);
    factory.setMaxConcurrentConsumers(concurrency);
    factory.setPrefetchCount(1);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    return factory;
  }
}
