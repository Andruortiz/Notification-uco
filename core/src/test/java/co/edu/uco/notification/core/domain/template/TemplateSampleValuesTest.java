package co.edu.uco.notification.core.domain.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.exception.UndeclaredTemplateVariableException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TemplateSampleValuesTest {

  private static final TenantId TENANT = TenantId.of("tenant-a");
  private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

  @Test
  void sampleValuesAreStoredWhenTheyNameDeclaredVariables() {
    final Template template = create("Hola {{nombre}} {{curso}}", Map.of("nombre", "Ana"));

    assertEquals(Map.of("nombre", "Ana"), template.sampleValues());
  }

  @Test
  void aTemplateCreatedWithoutSampleValuesHasAnEmptyMap() {
    final Template template = create("Hola {{nombre}}", null);

    assertTrue(template.sampleValues().isEmpty());
  }

  @Test
  void aSampleKeyNotPresentInTheTextIsRejected() {
    assertThrows(
        UndeclaredTemplateVariableException.class,
        () -> create("Hola {{nombre}}", Map.of("curso", "Math")));
  }

  @Test
  void moreThanFiftySampleEntriesAreRejected() {
    final String body =
        IntStream.range(0, 50).mapToObj(i -> "{{v" + i + "}}").collect(Collectors.joining(" "));
    final Map<String, String> samples =
        IntStream.range(0, 51).boxed().collect(Collectors.toMap(i -> "v" + i, i -> "x"));

    assertThrows(TemplateLimitExceededException.class, () -> create(body, samples));
  }

  @Test
  void aSampleValueLongerThanTwoThousandCharactersIsRejected() {
    assertThrows(
        TemplateLimitExceededException.class,
        () -> create("Hola {{nombre}}", Map.of("nombre", "x".repeat(2001))));
  }

  @Test
  void updateReplacesTheWholeSampleMap() {
    final Template template = create("Hola {{nombre}} {{curso}}", Map.of("nombre", "Ana"));

    final Template updated =
        template.update(
            TemplateCategory.ACADEMICO,
            null,
            "Hola {{curso}}",
            List.of(),
            Map.of("curso", "Math"),
            NOW);

    assertEquals(Map.of("curso", "Math"), updated.sampleValues());
  }

  private static Template create(final String body, final Map<String, String> samples) {
    return Template.create(
        TENANT,
        "welcome",
        TemplateCategory.ACADEMICO,
        ChannelType.of("EMAIL"),
        "es",
        null,
        body,
        List.of(),
        samples,
        NOW);
  }
}
