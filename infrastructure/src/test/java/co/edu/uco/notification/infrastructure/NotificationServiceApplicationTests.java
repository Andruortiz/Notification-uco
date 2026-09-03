package co.edu.uco.notification.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Verifies every use-case and adapter bean wires together (UseCaseConfig, RabbitConfig, and the
// Mongo/Rabbit/simulated-provider/static-catalog adapters) without needing a live Mongo or
// RabbitMQ broker. Reactive Mongo only opens a connection lazily on first use, so it's naturally
// fine either way -- but the @RabbitListener container eagerly connects and authenticates on
// startup, which would make this test's outcome depend on whatever Rabbit instance happens to be
// reachable at test time (absent, or present with different credentials). Disabling listener
// auto-startup keeps this test about wiring, not ambient infrastructure state.
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class NotificationServiceApplicationTests {

  @Test
  void contextLoads() {}
}
