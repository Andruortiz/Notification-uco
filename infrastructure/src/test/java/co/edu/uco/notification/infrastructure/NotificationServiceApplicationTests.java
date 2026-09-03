package co.edu.uco.notification.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Verifies every use-case and adapter bean wires together (UseCaseConfig, RabbitConfig, and the
// Mongo/Rabbit/simulated-provider/static-catalog adapters) without needing a live Mongo or
// RabbitMQ broker -- Spring Boot's reactive Mongo and AMQP autoconfiguration create their client
// beans eagerly but only open a real connection lazily, on first use.
@SpringBootTest
class NotificationServiceApplicationTests {

  @Test
  void contextLoads() {}
}
