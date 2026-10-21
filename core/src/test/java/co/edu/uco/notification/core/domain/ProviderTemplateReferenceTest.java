package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.ProviderTemplateReference;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderTemplateReferenceTest {

  @Test
  void keepsTheTemplateIdAndTheVariables() {
    final ProviderTemplateReference reference =
        ProviderTemplateReference.of("HX0001", Map.of("1", "Alice"));

    assertEquals("HX0001", reference.templateId());
    assertEquals(Map.of("1", "Alice"), reference.variables());
  }

  @Test
  void nullVariablesBecomeAnEmptyMap() {
    assertTrue(ProviderTemplateReference.of("HX0001", null).variables().isEmpty());
  }

  @Test
  void rejectsABlankTemplateId() {
    assertThrows(IllegalArgumentException.class, () -> ProviderTemplateReference.of(" ", Map.of()));
  }

  @Test
  void rejectsABlankVariableName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderTemplateReference.of("HX0001", Map.of(" ", "value")));
  }

  @Test
  void rejectsANullVariableValue() {
    final Map<String, String> variables = new HashMap<>();
    variables.put("1", null);

    assertThrows(
        NullPointerException.class, () -> ProviderTemplateReference.of("HX0001", variables));
  }

  @Test
  void theVariablesAreACopyThatCannotBeModified() {
    final Map<String, String> source = new HashMap<>(Map.of("1", "Alice"));
    final ProviderTemplateReference reference = ProviderTemplateReference.of("HX0001", source);

    source.put("2", "Bob");

    assertEquals(Map.of("1", "Alice"), reference.variables());
    assertThrows(UnsupportedOperationException.class, () -> reference.variables().put("3", "x"));
  }

  @Test
  void toStringDoesNotExposeTheVariableValues() {
    final String text = ProviderTemplateReference.of("HX0001", Map.of("1", "Alice")).toString();

    assertTrue(text.contains("HX0001"));
    assertFalse(text.contains("Alice"));
  }
}
