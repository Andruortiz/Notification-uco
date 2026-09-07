package co.edu.uco.notification.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "RABBITMQ_USERNAME=test",
      "RABBITMQ_PASSWORD=test"
    })
class NotificationServiceApplicationTests {

  @Test
  void contextLoads() {}
}
