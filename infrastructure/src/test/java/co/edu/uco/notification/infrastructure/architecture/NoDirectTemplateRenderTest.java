package co.edu.uco.notification.infrastructure.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import co.edu.uco.notification.core.domain.template.TemplateEngine;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NoDirectTemplateRenderTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("co.edu.uco.notification");
  }

  @Test
  void onlyTheSingleRendererMayCallTemplateEngineRender() {
    final ArchRule rule =
        noClasses()
            .that()
            .doNotHaveSimpleName("RenderTemplateService")
            .should()
            .callMethod(TemplateEngine.class, "render", String.class, Map.class)
            .because("todo render de plantillas pasa por RenderTemplateService (HU2-126 FR-009)");
    rule.check(classes);
  }
}
