package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.infrastructure.support.ControllableProvider;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.catalog.channels.EMAIL.providers[0]=circuit-a",
      "notification.catalog.channels.EMAIL.providers[1]=simulated",
      "notification.catalog.channels.SMS.providers[0]=circuit-b",
      "notification.catalog.channels.SMS.providers[1]=circuit-c",
      "notification.provider.circuit-breaker.minimum-calls=3",
      "notification.provider.circuit-breaker.sliding-window-size=3",
      "notification.provider.circuit-breaker.open-wait=8s",
      "notification.provider.circuit-breaker.half-open-calls=1",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class ProviderFailoverCircuitE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @TestConfiguration
  static class CircuitProvidersConfiguration {

    @Bean
    ControllableProvider circuitA() {
      return new ControllableProvider("circuit-a");
    }

    @Bean
    ControllableProvider circuitB() {
      return new ControllableProvider("circuit-b");
    }

    @Bean
    ControllableProvider circuitC() {
      return new ControllableProvider("circuit-c");
    }
  }

  @LocalServerPort private int port;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired
  @Qualifier("circuitA")
  private ControllableProvider circuitA;

  @Autowired
  @Qualifier("circuitB")
  private ControllableProvider circuitB;

  @Autowired
  @Qualifier("circuitC")
  private ControllableProvider circuitC;

  private FailoverClient client;

  @BeforeEach
  void setUp() {
    client = new FailoverClient(port);
  }

  private double attemptsOf(final String provider) {
    return meterRegistry.find("notification.attempts").tag("provider", provider).counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  private Message awaitDeadLettered(final String notificationId) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
    while (Instant.now().isBefore(deadline)) {
      final Message received = rabbitTemplate.receive(rabbitTopologyProperties.dlq().queue(), 500);
      if (received != null
          && notificationId.equals(new String(received.getBody(), StandardCharsets.UTF_8))) {
        return received;
      }
    }
    return null;
  }

  @Test
  void anOpenCircuitIsSkippedWithoutInvocationAndAdmitsATrialAfterTheWait() {
    for (int i = 0; i < 3; i++) {
      final String id = client.accept("tenant-1", "circuit-a-" + i, "EMAIL", "recipient-circuit-a");
      client.awaitStatus("tenant-1", id, "DELIVERED");
    }
    assertEquals(3, circuitA.invocations());

    final Instant startedAt = Instant.now();
    final String skipped =
        client.accept("tenant-1", "circuit-a-skip", "EMAIL", "recipient-circuit-a");
    final Map<String, Object> delivered = client.awaitStatus("tenant-1", skipped, "DELIVERED");
    final Duration elapsed = Duration.between(startedAt, Instant.now());

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals(3, circuitA.invocations());
    assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0, "took " + elapsed);
    final List<Map<String, Object>> attempts =
        client.attempts("tenant-1", "recipient-circuit-a", skipped);
    assertEquals(1, attempts.size());
    assertEquals("simulated", attempts.get(0).get("providerId"));
    assertEquals(Boolean.FALSE, attempts.get(0).get("failedOver"));

    circuitA.respondWith(AttemptResult.ACCEPTED);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    String viaA = null;
    int n = 0;
    while (viaA == null && Instant.now().isBefore(deadline)) {
      final String id =
          client.accept("tenant-1", "circuit-a-trial-" + n++, "EMAIL", "recipient-circuit-a");
      final Map<String, Object> current = client.awaitStatus("tenant-1", id, "DELIVERED");
      if ("circuit-a".equals(current.get("providerId"))) {
        viaA = id;
      }
    }
    assertNotNull(viaA, "after the wait the circuit must admit a trial and deliver");
  }

  @Test
  void whenEveryCandidateIsOpenNothingIsSentAndTheMessageFollowsTheDeadLetterPath() {
    for (int i = 0; i < 3; i++) {
      final String id = client.accept("tenant-1", "circuit-bc-" + i, "SMS", "recipient-circuit-bc");
      client.awaitStatus("tenant-1", id, "RECOVERABLE");
    }
    assertEquals(3, circuitB.invocations());
    assertEquals(3, circuitC.invocations());
    final double attemptsB = attemptsOf("circuit-b");
    final double attemptsC = attemptsOf("circuit-c");

    final String blocked =
        client.accept("tenant-1", "circuit-bc-blocked", "SMS", "recipient-circuit-bc");
    final Message deadLettered = awaitDeadLettered(blocked);

    assertNotNull(deadLettered, "the message must end in the dead letter queue");
    assertEquals(3, circuitB.invocations());
    assertEquals(3, circuitC.invocations());
    assertEquals(attemptsB, attemptsOf("circuit-b"));
    assertEquals(attemptsC, attemptsOf("circuit-c"));
    assertEquals("PENDING", client.detail("tenant-1", blocked).get("status"));
    assertTrue(client.attempts("tenant-1", "recipient-circuit-bc", blocked).isEmpty());

    circuitB.respondWith(AttemptResult.ACCEPTED);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    boolean delivered = false;
    int n = 0;
    while (!delivered && Instant.now().isBefore(deadline)) {
      final String id =
          client.accept("tenant-1", "circuit-bc-trial-" + n++, "SMS", "recipient-circuit-bc");
      final Map<String, Object> current = client.awaitStatus("tenant-1", id, "DELIVERED");
      delivered = "DELIVERED".equals(current.get("status"));
    }
    assertTrue(delivered, "after the wait the circuit must admit a trial and deliver");
  }
}
