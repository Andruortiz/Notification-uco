package co.edu.uco.notification.core.domain.template;

import java.util.List;

public record TemplatePreview(
    String subject,
    String body,
    List<String> missingVariables,
    int characterCount,
    int subjectCharacterCount,
    SmsSegmentation sms) {

  public TemplatePreview {
    missingVariables = List.copyOf(missingVariables);
  }
}
