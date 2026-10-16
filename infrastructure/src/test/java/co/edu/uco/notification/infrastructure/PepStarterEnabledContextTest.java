package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.server.WebFilter;

@SpringBootTest(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "RABBITMQ_USERNAME=test",
      "RABBITMQ_PASSWORD=test",
      "security.enabled=true",
      "security.pep.enforcement.enabled=true",
      "security.pep.enforcement.pep-url=http://localhost:18081",
      "security.pep.enforcement.allow-insecure-http=true",
      "security.pep.enforcement.application-name=notification-service",
      "security.pep.enforcement.environment=test",
      "security.pep.enforcement.application-credential=test-credential"
    })
class PepStarterEnabledContextTest {

  @Autowired private ApplicationContext context;

  @Test
  void theContextStartsWithTheStarterEnabledAndRegistersItsFilterOnce() {
    final List<String> filters =
        context.getBeansOfType(WebFilter.class).values().stream()
            .map(filter -> filter.getClass().getName())
            .filter(name -> name.contains("PepEnforcementWebFilter"))
            .toList();

    assertEquals(1, filters.size(), filters.toString());
  }
}
