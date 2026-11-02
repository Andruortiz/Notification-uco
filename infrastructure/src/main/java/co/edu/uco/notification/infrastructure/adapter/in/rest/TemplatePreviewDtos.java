package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.template.SmsSegmentation;
import co.edu.uco.notification.core.domain.template.TemplatePreview;
import java.util.List;
import java.util.Map;

public final class TemplatePreviewDtos {

  private TemplatePreviewDtos() {}

  public record PreviewTemplateRequest(
      String channel,
      String subject,
      String body,
      List<TemplateDtos.VariableDto> variables,
      Map<String, String> values) {}

  public record SmsSegmentationResponse(
      String encoding, int units, int unitsPerSegment, int segments, boolean exceedsSingleSegment) {

    static SmsSegmentationResponse from(final SmsSegmentation sms) {
      return new SmsSegmentationResponse(
          sms.encoding().name(),
          sms.units(),
          sms.unitsPerSegment(),
          sms.segments(),
          sms.exceedsSingleSegment());
    }
  }

  public record TemplatePreviewResponse(
      String subject,
      String body,
      List<String> missingVariables,
      int characterCount,
      int subjectCharacterCount,
      SmsSegmentationResponse sms) {

    static TemplatePreviewResponse from(final TemplatePreview preview) {
      return new TemplatePreviewResponse(
          preview.subject(),
          preview.body(),
          preview.missingVariables(),
          preview.characterCount(),
          preview.subjectCharacterCount(),
          preview.sms() == null ? null : SmsSegmentationResponse.from(preview.sms()));
    }
  }
}
