package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.adapter.out.provider.SimulatedNotificationProvider;
import co.edu.uco.notification.infrastructure.support.ControllableProvider;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import java.util.List;
import java.util.Map;
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
      "notification.provider.simulated.enabled=false",
      "notification.catalog.channels.EMAIL.providers[0]=failover-off",
      "notification.catalog.channels.EMAIL.providers[1]=simulated",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class ProviderFailoverSimulatedDisabledE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @TestConfiguration
  static class FailingProviderConfiguration {

    @Bean
    ControllableProvider failoverOff() {
      return new ControllableProvider("failover-off");
    }
  }

  @LocalServerPort private int port;

  @Autowired private SimulatedNotificationProvider simulated;

  @Test
  void simulatedNeverDeliversWhenDisabledAndTheNotificationStaysRecoverable() {
    final FailoverClient client = new FailoverClient(port);

    final String id = client.accept("tenant-1", "failover-off-1", "EMAIL", "recipient-off");
    final Map<String, Object> current = client.awaitStatus("tenant-1", id, "RECOVERABLE");

    assertTrue(simulated.disabledReason().isPresent());
    assertEquals("RECOVERABLE", current.get("status"));
    final List<Map<String, Object>> attempts = client.attempts("tenant-1", "recipient-off", id);
    assertEquals(1, attempts.size());
    assertEquals("failover-off", attempts.get(0).get("providerId"));
    assertEquals("RECOVERABLE_FAILURE", attempts.get(0).get("result"));
    assertTrue(attempts.stream().noneMatch(a -> "simulated".equals(a.get("providerId"))));
  }
}
