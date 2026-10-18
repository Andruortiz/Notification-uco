package co.edu.uco.notification.infrastructure.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ForbiddenImportsTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes = new ClassFileImporter().importPackages("co.edu.uco.notification");
  }

  @Test
  void noClassUsesJackson2DatabindOrCore() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void noClassUsesTheDeprecatedEnvironmentPostProcessorContract() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.boot.env.EnvironmentPostProcessor")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void noClassUsesRelocatedBoot3Packages() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.boot.actuate.health..",
            "org.springframework.boot.autoconfigure.amqp..",
            "org.springframework.boot.test.mock.mockito..",
            "org.springframework.boot.test.autoconfigure..")
        .allowEmptyShould(true)
        .check(classes);
  }
}
