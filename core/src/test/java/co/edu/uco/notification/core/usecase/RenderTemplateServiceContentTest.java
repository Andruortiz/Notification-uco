package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.exception.InvalidTemplateSyntaxException;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.repository.TemplateRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RenderTemplateServiceContentTest {

  private final RenderTemplateService service =
      new RenderTemplateService(mock(TemplateRepository.class));

  @Test
  void contentWithoutTemplateIdIsRenderedAndKeepsTheChannel() {
    final ResolvedTemplate resolved =
        service.resolveContent(
            ChannelType.of("SMS"),
            "Asunto {{nombre}}",
            "Hola {{nombre}}",
            List.of(new TemplateVariable("nombre", true)),
            Map.of("nombre", "Ana"));

    assertEquals("Asunto Ana", resolved.subject());
    assertEquals("Hola Ana", resolved.body());
    assertEquals(ChannelType.of("SMS"), resolved.channelType());
    assertNull(resolved.category());
    assertNull(resolved.critical());
    assertTrue(resolved.blockRefs().isEmpty());
  }

  @Test
  void missingVariablesAreTolerated() {
    final ResolvedTemplate resolved =
        service.resolveContent(
            ChannelType.of("EMAIL"),
            null,
            "Hola {{nombre}}, curso {{curso}}",
            List.of(new TemplateVariable("curso", false)),
            Map.of());

    assertEquals("Hola , curso ", resolved.body());
    assertNull(resolved.subject());
    assertEquals(
        List.of(new TemplateVariable("nombre", true), new TemplateVariable("curso", false)),
        resolved.variables());
  }

  @Test
  void aValueContainingAnotherPlaceholderIsInsertedLiterally() {
    final ResolvedTemplate resolved =
        service.resolveContent(
            ChannelType.of("EMAIL"),
            null,
            "{{a}}",
            List.of(),
            Map.of("a", "{{otra}}", "otra", "X"));

    assertEquals("{{otra}}", resolved.body());
  }

  @Test
  void invalidSyntaxIsRejected() {
    assertThrows(
        InvalidTemplateSyntaxException.class,
        () ->
            service.resolveContent(
                ChannelType.of("EMAIL"), null, "Hola {{1abc}}", List.of(), Map.of()));
  }

  @Test
  void tooManyValuesAreRejectedWithTheLimitException() {
    final Map<String, String> values =
        IntStream.range(0, 51).boxed().collect(Collectors.toMap(i -> "v" + i, i -> "x"));

    assertThrows(
        TemplateLimitExceededException.class,
        () -> service.resolveContent(ChannelType.of("EMAIL"), null, "Hola", List.of(), values));
  }
}
