package co.edu.uco.notification.infrastructure.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HexagonalArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("co.edu.uco.notification");
  }

  @Test
  void coreMustNotDependOnInfrastructure() {
    // allowEmptyShould: core aún no tiene clases; la regla igual queda activa para cuando las haya.
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("co.edu.uco.notification.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("co.edu.uco.notification.infrastructure..")
            .allowEmptyShould(true);
    rule.check(classes);
  }

  @Test
  void coreMustNotDependOnSpringFramework() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("co.edu.uco.notification.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .allowEmptyShould(true);
    rule.check(classes);
  }

  @Test
  void portsMustNotDependOnAdapters() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("co.edu.uco.notification.core.port..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("co.edu.uco.notification.infrastructure.adapter..")
            .allowEmptyShould(true);
    rule.check(classes);
  }
}
