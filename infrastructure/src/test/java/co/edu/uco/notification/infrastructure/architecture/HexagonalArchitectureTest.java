package co.edu.uco.notification.infrastructure.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
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
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("co.edu.uco.notification.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("co.edu.uco.notification.infrastructure..");
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
            .resideInAPackage("org.springframework..");
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
            .resideInAPackage("co.edu.uco.notification.infrastructure.adapter..");
    rule.check(classes);
  }

  @Test
  void domainMustNotDependOnUseCases() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("co.edu.uco.notification.core.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("co.edu.uco.notification.core.usecase..");
    rule.check(classes);
  }

  @Test
  void inboundAdaptersMustNotDependOnOutboundAdapters() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("co.edu.uco.notification.infrastructure.adapter.in..")
            .should()
            .dependOnClassesThat(
                resideInAPackage("co.edu.uco.notification.infrastructure.adapter.out..")
                    .and(not(simpleName("AttachmentScanRequest"))));
    rule.check(classes);
  }
}
