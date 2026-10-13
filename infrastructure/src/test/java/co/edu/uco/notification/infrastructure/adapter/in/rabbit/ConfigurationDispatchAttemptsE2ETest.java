package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.infrastructure.support.FakeParametersSource;
import co.edu.uco.notification.infrastructure.support.FakeParametersSourceConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.parameters.poll-interval-ms=1000"
    })
@Testcontainers
@Import(FakeParametersSourceConfig.class)
class ConfigurationDispatchAttemptsE2ETest {

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration ADOPTION_MARGIN = Duration.ofSeconds(2);
  private static final Duration ADOPTION_LIMIT = Duration.ofSeconds(15);

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @MockBean private DispatchNotificationUseCase dispatchNotificationUseCase;

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitTopologyProperties topology;
  @Autowired private ConfigurationView view;
  @Autowired private FakeParametersSource fakeSource;

  private final Map<String, AtomicInteger> dispatches = new ConcurrentHashMap<>();

  @BeforeEach
  void failEveryDispatchAndCountIt() {
    fakeSource.publishNextAndAwaitAdoption(view, maxAttempts(3), ADOPTION_LIMIT);
    assertEquals(3, view.snapshot().dispatchMaxAttempts());
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

  private static Map<String, Object> maxAttempts(final long value) {
    return Map.of(ParameterRegistry.DISPATCH_MAX_ATTEMPTS, value);
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

    final Duration adoption =
        fakeSource.publishNextAndAwaitAdoption(view, maxAttempts(5), ADOPTION_LIMIT);
    assertEquals(5, view.snapshot().dispatchMaxAttempts());
    assertTrue(
        adoption.compareTo(POLL_INTERVAL.plus(ADOPTION_MARGIN)) < 0, "adoption took " + adoption);

    final NotificationId after = publishFailingMessage();
    assertNotNull(awaitDeadLetter(after));
    assertEquals(5, dispatchesOf(after));
  }
}
