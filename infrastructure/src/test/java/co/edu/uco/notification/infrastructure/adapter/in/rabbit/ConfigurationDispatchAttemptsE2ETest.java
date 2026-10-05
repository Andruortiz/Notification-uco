package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.core.usecase.ConfigurationHolder;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class ConfigurationDispatchAttemptsE2ETest {

  private static final Duration ADOPTION_BUDGET = Duration.ofSeconds(15);

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @MockBean private DispatchNotificationUseCase dispatchNotificationUseCase;

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitTopologyProperties topology;
  @Autowired private ConfigurationHolder holder;

  private final Map<String, AtomicInteger> dispatches = new ConcurrentHashMap<>();

  @BeforeEach
  void failEveryDispatchAndCountIt() {
    holder.replace(withMaxAttempts(3));
    when(dispatchNotificationUseCase.dispatch(any()))
        .thenAnswer(
            invocation -> {
              final NotificationId id = invocation.getArgument(0);
              dispatches.computeIfAbsent(id.value(), key -> new AtomicInteger()).incrementAndGet();
              return Mono.error(new NotificationNotFoundException(id));
            });
    while (rabbitTemplate.receive(topology.dlq().queue(), 100) != null) {
      Thread.onSpinWait();
    }
  }

  private ConfigurationSnapshot withMaxAttempts(final long maxAttempts) {
    final ConfigurationSnapshot current = holder.snapshot();
    final Map<String, Long> values = new HashMap<>(current.values());
    values.put(ParameterRegistry.DISPATCH_MAX_ATTEMPTS, maxAttempts);
    return new ConfigurationSnapshot(
        current.version() + 1,
        ConfigurationSource.DEFAULTS,
        values,
        Instant.now(),
        current.pendingRestart());
  }

  private NotificationId publishFailingMessage() {
    final NotificationId id = NotificationId.newId();
    rabbitTemplate.convertAndSend(
        topology.dispatch().exchange(),
        topology.dispatch().routingKey(),
        id.value(),
        message -> {
          message.getMessageProperties().setMessageId(UUID.randomUUID().toString());
          return message;
        });
    return id;
  }

  private Message awaitDeadLetter(final NotificationId id) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final Message received = rabbitTemplate.receive(topology.dlq().queue(), 500);
      if (received != null
          && id.value().equals(new String(received.getBody(), StandardCharsets.UTF_8))) {
        return received;
      }
    }
    return null;
  }

  private int dispatchesOf(final NotificationId id) {
    final AtomicInteger count = dispatches.get(id.value());
    return count == null ? 0 : count.get();
  }

  @Test
  void withTheDefaultOfThreeAFailingMessageIsAttemptedThreeTimesBeforeTheDeadLetterQueue() {
    final NotificationId id = publishFailingMessage();

    assertNotNull(awaitDeadLetter(id));

    assertEquals(3, dispatchesOf(id));
  }

  @Test
  void aValuePublishedAsFiveGovernsTheNextMessageWithinTheAdoptionBudget() {
    final NotificationId before = publishFailingMessage();
    assertNotNull(awaitDeadLetter(before));
    assertEquals(3, dispatchesOf(before));

    final Instant started = Instant.now();
    holder.replace(withMaxAttempts(5));
    final NotificationId after = publishFailingMessage();
    final Message deadLettered = awaitDeadLetter(after);
    final Duration elapsed = Duration.between(started, Instant.now());

    assertNotNull(deadLettered);
    assertEquals(5, dispatchesOf(after));
    assertTrue(
        elapsed.compareTo(ADOPTION_BUDGET) < 0,
        "el cambio debe regir en el siguiente mensaje, sin esperar ni reiniciar");
  }
}
