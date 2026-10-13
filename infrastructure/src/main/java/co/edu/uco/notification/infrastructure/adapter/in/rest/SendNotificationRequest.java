package co.edu.uco.notification.infrastructure.adapter.in.rest;

import java.util.List;

public record SendNotificationRequest(
    String externalId,
    String channelType,
    String recipientId,
    String recipientAddress,
    String subject,
    String body,
    String priority,
    List<AttachmentRequest> attachments) {

  public SendNotificationRequest {
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }
}
