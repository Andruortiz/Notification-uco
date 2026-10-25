package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.support.ControllableProvider;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
      "notification.catalog.channels.EMAIL.providers[0]=failover-a",
      "notification.catalog.channels.EMAIL.providers[1]=simulated",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class ProviderFailoverE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @TestConfiguration
  static class FailingProviderConfiguration {

    @Bean
    ControllableProvider failoverA() {
      return new ControllableProvider("failover-a");
    }
  }

  @LocalServerPort private int port;

  @Autowired private ControllableProvider failoverA;

  private FailoverClient client;

  @BeforeEach
  void setUp() {
    client = new FailoverClient(port);
  }

  @Test
  void aRecoverableFailureOfThePreferredProviderIsDeliveredThroughTheNextOneInOneDispatch() {
    final int invocationsBefore = failoverA.invocations();
    final String id = client.accept("tenant-1", "failover-1", "EMAIL", "recipient-failover-1");
    final Instant startedAt = Instant.now();
    final Map<String, Object> delivered = client.awaitStatus("tenant-1", id, "DELIVERED");
    final Duration elapsed = Duration.between(startedAt, Instant.now());

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("simulated", delivered.get("providerId"));
    assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0, "took " + elapsed);
    assertEquals(invocationsBefore + 1, failoverA.invocations());
    final List<Map<String, Object>> attempts =
        client.attempts("tenant-1", "recipient-failover-1", id);
    assertEquals(2, attempts.size());
    assertEquals("failover-a", attempts.get(0).get("providerId"));
    assertEquals("RECOVERABLE_FAILURE", attempts.get(0).get("result"));
    assertEquals(Boolean.TRUE, attempts.get(0).get("failedOver"));
    assertEquals("simulated", attempts.get(1).get("providerId"));
    assertEquals("ACCEPTED", attempts.get(1).get("result"));
    assertEquals(Boolean.FALSE, attempts.get(1).get("failedOver"));
  }

  @Test
  void aFailoverOfOneTenantDoesNotAppearInAnotherTenant() {
    final String first = client.accept("tenant-1", "failover-iso-1", "EMAIL", "recipient-iso");
    final String second = client.accept("tenant-2", "failover-iso-2", "EMAIL", "recipient-iso");
    client.awaitStatus("tenant-1", first, "DELIVERED");
    client.awaitStatus("tenant-2", second, "DELIVERED");

    assertEquals(2, client.attempts("tenant-1", "recipient-iso", first).size());
    assertEquals(2, client.attempts("tenant-2", "recipient-iso", second).size());
    assertTrue(client.attempts("tenant-2", "recipient-iso", first).isEmpty());
    assertTrue(client.attempts("tenant-1", "recipient-iso", second).isEmpty());
    assertEquals(1, client.itemCount("tenant-1", "recipient-iso"));
    assertEquals(1, client.itemCount("tenant-2", "recipient-iso"));
  }
}
