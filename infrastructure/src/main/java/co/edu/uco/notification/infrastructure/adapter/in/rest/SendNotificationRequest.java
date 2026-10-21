package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import java.util.List;

public record SendNotificationRequest(
    String externalId,
    String channelType,
    String recipientId,
    String recipientAddress,
    String subject,
    String body,
    ProviderTemplateRequest providerTemplate,
    String priority,
    List<AttachmentRequest> attachments) {

  public SendNotificationRequest {
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  NotificationContent toContent() {
    if (providerTemplate == null) {
      return NotificationContent.of(subject, body);
    }
    return NotificationContent.of(subject, body, List.of(), providerTemplate.toReference());
  }
}
