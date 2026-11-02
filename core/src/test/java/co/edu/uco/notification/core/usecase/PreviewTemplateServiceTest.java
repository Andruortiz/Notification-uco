package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import co.edu.uco.notification.core.domain.template.SmsEncoding;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.exception.InvalidTemplateSyntaxException;
import co.edu.uco.notification.core.port.in.PreviewCommand;
import co.edu.uco.notification.core.repository.TemplateRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class PreviewTemplateServiceTest {

  private final TemplateRepository repository = mock(TemplateRepository.class);
  private final PreviewTemplateService service =
      new PreviewTemplateService(new RenderTemplateService(repository));

  @Test
  void rendersTheDraftWithTheGivenValuesAndNoMissingVariables() {
    StepVerifier.create(
            service.preview(
                new PreviewCommand(
                    ChannelType.of("EMAIL"),
                    null,
                    "Hola {{nombre}} {{curso}}",
                    List.of(
                        new TemplateVariable("nombre", true), new TemplateVariable("curso", true)),
                    Map.of("nombre", "Ana", "curso", "Math"))))
        .assertNext(
            preview -> {
              assertEquals("Hola Ana Math", preview.body());
              assertTrue(preview.missingVariables().isEmpty());
            })
        .verifyComplete();
  }

  @Test
  void aMissingRequiredVariableRendersEmptyAndIsListed() {
    StepVerifier.create(
            service.preview(
                new PreviewCommand(
                    ChannelType.of("EMAIL"),
                    null,
                    "Hola {{nombre}} {{curso}}",
                    List.of(
                        new TemplateVariable("nombre", true), new TemplateVariable("curso", true)),
                    Map.of("nombre", "Ana"))))
        .assertNext(
            preview -> {
              assertEquals("Hola Ana ", preview.body());
              assertEquals(List.of("curso"), preview.missingVariables());
            })
        .verifyComplete();
  }

  @Test
  void aValueContainingAPlaceholderIsInsertedLiterally() {
    StepVerifier.create(
            service.preview(
                new PreviewCommand(
                    ChannelType.of("EMAIL"),
                    null,
                    "{{nombre}}",
                    List.of(new TemplateVariable("nombre", true)),
                    Map.of("nombre", "{{otra}}"))))
        .assertNext(preview -> assertEquals("{{otra}}", preview.body()))
        .verifyComplete();
  }

  @Test
  void invalidSyntaxPropagatesWithoutTouchingTheRepository() {
    StepVerifier.create(
            service.preview(
                new PreviewCommand(ChannelType.of("EMAIL"), null, "{{1abc}}", List.of(), Map.of())))
        .expectError(InvalidTemplateSyntaxException.class)
        .verify();
    verifyNoInteractions(repository);
  }

  @Test
  void smsPreviewCountsTheRenderedBodyAndReportsSegments() {
    StepVerifier.create(
            service.preview(
                new PreviewCommand(
                    ChannelType.of("SMS"),
                    null,
                    "Hola {{nombre}} 😀 tu cita es el lunes",
                    List.of(new TemplateVariable("nombre", true)),
                    Map.of("nombre", "Ana"))))
        .assertNext(
            preview -> {
              assertEquals(30, preview.characterCount());
              assertEquals(0, preview.subjectCharacterCount());
              assertEquals(SmsEncoding.UCS2, preview.sms().encoding());
              assertEquals(31, preview.sms().units());
              assertEquals(1, preview.sms().segments());
            })
        .verifyComplete();
  }

  @Test
  void nonSmsPreviewHasNoSmsSegmentationButKeepsTheCounts() {
    StepVerifier.create(
            service.preview(
                new PreviewCommand(
                    ChannelType.of("EMAIL"),
                    "Asunto",
                    "Hola {{nombre}}",
                    List.of(new TemplateVariable("nombre", true)),
                    Map.of("nombre", "Ana"))))
        .assertNext(
            preview -> {
              assertNull(preview.sms());
              assertEquals(8, preview.characterCount());
              assertEquals(6, preview.subjectCharacterCount());
            })
        .verifyComplete();
  }
}
