package co.edu.uco.notification.core.domain.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.exception.InvalidTemplateSyntaxException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateEngineTest {

  private static final Duration LIMIT = Duration.ofMillis(200);

  @Test
  void detectsWithoutDuplicatesInOrderOfAppearanceAcrossSubjectAndBody() {
    assertEquals(
        List.of("curso", "nombre", "fecha"),
        List.copyOf(
            TemplateEngine.detect(
                "Hola {{curso}}", "{{nombre}} {{curso}} {{ fecha }} {{nombre}}")));
  }

  @Test
  void toleratesUpToFiveSpacesInsideTheBraces() {
    assertEquals(List.of("a"), List.copyOf(TemplateEngine.detect("{{     a     }}")));
  }

  @Test
  void rejectsMoreThanFiveSpacesInsideTheBraces() {
    assertThrows(
        InvalidTemplateSyntaxException.class, () -> TemplateEngine.detect("{{      nombre}}"));
  }

  @Test
  void rejectsInvalidNamesAndUnbalancedBraces() {
    assertThrows(InvalidTemplateSyntaxException.class, () -> TemplateEngine.detect("{{1abc}}"));
    assertThrows(InvalidTemplateSyntaxException.class, () -> TemplateEngine.detect("{{abc"));
    assertThrows(InvalidTemplateSyntaxException.class, () -> TemplateEngine.detect("abc}}"));
    assertThrows(InvalidTemplateSyntaxException.class, () -> TemplateEngine.detect("{{ }}"));
    assertThrows(
        InvalidTemplateSyntaxException.class,
        () -> TemplateEngine.detect("{{" + "a".repeat(41) + "}}"));
  }

  @Test
  void acceptsTextWithoutVariablesAndSingleBraces() {
    assertTrue(TemplateEngine.detect("plain { text } here", null).isEmpty());
  }

  @Test
  void rendersInASinglePassTreatingValuesAsLiteralText() {
    final String rendered =
        TemplateEngine.render(
            "Hola {{nombre}} / {{ otra }} / {{nada}}",
            Map.of("nombre", "{{otra}} $1 \\", "otra", "X"));

    assertEquals("Hola {{otra}} $1 \\ / X / ", rendered);
  }

  @Test
  void detectsAndRendersTheLargestTemplateWithinTheTimeLimit() {
    final StringBuilder body = new StringBuilder();
    final Map<String, String> values = new HashMap<>();
    for (int i = 0; i < 50; i++) {
      body.append("{{ v").append(i).append(" }} ");
      values.put("v" + i, "valor");
    }
    final String text = body + "x".repeat(Template.MAX_BODY - body.length());

    assertEquals(50, assertTimeout(LIMIT, () -> TemplateEngine.detect(text)).size());
    final String rendered = assertTimeout(LIMIT, () -> TemplateEngine.render(text, values));
    assertTrue(rendered.startsWith("valor valor "));
    assertTrue(!rendered.contains("{{"));
  }

  @Test
  void adversarialInputIsRejectedWithinTheTimeLimit() {
    final String adversarial = "{{" + " ".repeat(19990);
    final String manyOpenings = "{{ ".repeat(6000);

    assertTimeout(
        LIMIT,
        () -> {
          assertThrows(
              InvalidTemplateSyntaxException.class, () -> TemplateEngine.detect(adversarial));
          assertThrows(
              InvalidTemplateSyntaxException.class, () -> TemplateEngine.detect(manyOpenings));
        });
  }
}
