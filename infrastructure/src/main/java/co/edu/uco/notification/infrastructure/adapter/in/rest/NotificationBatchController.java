package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchNotificationItem;
import co.edu.uco.notification.core.port.in.SendNotificationBatchCommand;
import co.edu.uco.notification.core.port.in.SendNotificationBatchUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class NotificationBatchController {

  private final SendNotificationBatchUseCase sendNotificationBatchUseCase;

  public NotificationBatchController(
      final SendNotificationBatchUseCase sendNotificationBatchUseCase) {
    this.sendNotificationBatchUseCase =
        Preconditions.requireNonNull(
            sendNotificationBatchUseCase, "sendNotificationBatchUseCase must not be null");
  }

  @PostMapping("/notifications:sendBatch")
  public Mono<ResponseEntity<BatchAcceptedResponse>> sendBatch(
      @RequestHeader("X-Tenant-Id") final String tenantId,
      @RequestBody final SendNotificationBatchRequest request) {
    return sendNotificationBatchUseCase
        .sendBatch(toCommand(tenantId, request))
        .map(BatchAcceptedResponse::from)
        .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response));
  }

  private static SendNotificationBatchCommand toCommand(
      final String tenantId, final SendNotificationBatchRequest request) {
    if (request.items() == null || request.items().isEmpty()) {
      throw new IllegalArgumentException("items must not be empty");
    }
    return new SendNotificationBatchCommand(
        TenantId.of(tenantId),
        request.batchId() == null ? null : BatchId.of(request.batchId()),
        request.items().stream().map(NotificationBatchController::toItem).toList());
  }

  private static BatchNotificationItem toItem(final SendNotificationRequest item) {
    if (item == null) {
      throw new IllegalArgumentException("batch item must not be null");
    }
    if (item.priority() == null) {
      throw new IllegalArgumentException("priority must not be blank");
    }
    return new BatchNotificationItem(
        ExternalId.of(item.externalId()),
        ChannelType.of(item.channelType()),
        RecipientId.of(item.recipientId()),
        Recipient.of(item.recipientAddress()),
        NotificationContent.of(item.subject(), item.body()),
        Priority.valueOf(item.priority()),
        item.attachments().stream().map(AttachmentRequest::toSubmission).toList());
  }
}
