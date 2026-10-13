package co.edu.uco.notification.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

  private final ApplicationModules modules =
      ApplicationModules.of(NotificationServiceApplication.class);

  @Test
  void verifiesModularStructure() {
    modules.verify();
  }

  @Test
  void writesDocumentation() {
    new Documenter(modules).writeDocumentation();
  }
}
