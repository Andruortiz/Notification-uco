package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import co.edu.uco.notification.core.port.in.ReceivePublishedConfigurationUseCase;
import co.edu.uco.notification.infrastructure.adapter.in.rabbit.ParametersEventListener;
import co.edu.uco.notification.infrastructure.adapter.in.scheduler.ConfigurationEventLogger;
import co.edu.uco.notification.infrastructure.config.ParametersEventsProperties;
import co.edu.uco.notification.infrastructure.config.ParametersEventsRabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ParametersEventsInactiveTest {

  private static final Duration STARTUP_LIMIT = Duration.ofSeconds(30);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              PropertiesConfig.class,
              ParametersEventsRabbitConfig.class,
              ParametersEventListener.class,
              ConfigurationEventLogger.class)
          .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
          .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
          .withBean(
              SimpleRabbitListenerContainerFactoryConfigurer.class,
              () -> mock(SimpleRabbitListenerContainerFactoryConfigurer.class))
          .withBean(
              ReceivePublishedConfigurationUseCase.class,
              () -> mock(ReceivePublishedConfigurationUseCase.class))
          .withBean(ObjectMapper.class, ObjectMapper::new);

  @Configuration
  @EnableConfigurationProperties(ParametersEventsProperties.class)
  static class PropertiesConfig {}

  @Test
  void withoutAnExchangeNoTopologyNoContainerFactoryAndNoRecovererExist() {
    final Instant started = Instant.now();

    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          assertEquals(0, context.getBeansOfType(Queue.class).size());
          assertEquals(0, context.getBeansOfType(Binding.class).size());
          assertEquals(
              0, context.getBeansOfType(SimpleRabbitListenerContainerFactory.class).size());
          assertTrue(context.getBeansOfType(ParametersEventListener.class).isEmpty());
          assertTrue(
              Duration.between(started, Instant.now()).compareTo(STARTUP_LIMIT) <= 0,
              "startup took too long");
        });
  }

  @Test
  void aBlankExchangeBehavesAsInactive() {
    runner
        .withPropertyValues("notification.parameters.events.exchange=   ")
        .run(context -> assertEquals(0, context.getBeansOfType(Queue.class).size()));
  }

  @Test
  void withAnExchangeTheQueueTheDeadLetterQueueTheBindingsAndTheListenerExist() {
    runner
        .withPropertyValues(
            "notification.parameters.events.exchange=parameters.exchange",
            "notification.parameters.events.routing-key=config.changed",
            "notification.parameters.events.queue=parameters.queue")
        .run(
            context -> {
              final java.util.Map<String, Queue> queues = context.getBeansOfType(Queue.class);
              assertEquals(2, queues.size());
              final Queue main = context.getBean("parametersEventsQueue", Queue.class);
              assertEquals("parameters.queue", main.getName());
              assertEquals(
                  "parameters.queue.dlq",
                  context.getBean("parametersEventsDlqQueue", Queue.class).getName());
              assertEquals(
                  "parameters.queue.dlq", main.getArguments().get("x-dead-letter-routing-key"));
              assertEquals(2, context.getBeansOfType(Binding.class).size());
              assertNotNull(context.getBean(ParametersEventListener.class));
              assertNotNull(
                  context.getBean(
                      "parametersEventsListenerContainerFactory",
                      SimpleRabbitListenerContainerFactory.class));
            });
  }

  @Test
  void anExchangeWithoutQueueOrRoutingKeyFailsTheStartupNamingTheProperty() {
    runner
        .withPropertyValues("notification.parameters.events.exchange=parameters.exchange")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  context.getStartupFailure().toString().contains("notification.parameters.events"),
                  context.getStartupFailure().toString());
            });
  }
}
