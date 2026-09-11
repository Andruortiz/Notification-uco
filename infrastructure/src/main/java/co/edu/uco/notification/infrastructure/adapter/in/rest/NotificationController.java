package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.GetNotificationStatusQuery;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

  private final SendNotificationUseCase sendNotificationUseCase;
  private final GetNotificationStatusUseCase getNotificationStatusUseCase;

  public NotificationController(
      final SendNotificationUseCase sendNotificationUseCase,
      final GetNotificationStatusUseCase getNotificationStatusUseCase) {
    this.sendNotificationUseCase =
        Preconditions.requireNonNull(
            sendNotificationUseCase, "sendNotificationUseCase must not be null");
    this.getNotificationStatusUseCase =
        Preconditions.requireNonNull(
            getNotificationStatusUseCase, "getNotificationStatusUseCase must not be null");
  }

  @PostMapping
  public Mono<ResponseEntity<SendNotificationResponse>> send(
      @RequestHeader("X-Tenant-Id") final String tenantId,
      @RequestBody final SendNotificationRequest request) {
    return sendNotificationUseCase
        .send(toCommand(tenantId, request))
        .map(SendNotificationResponse::from)
        .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response));
  }

  @GetMapping("/{id}")
  public Mono<NotificationStatusResponse> getStatus(
      @RequestHeader("X-Tenant-Id") final String tenantId, @PathVariable("id") final String id) {
    final GetNotificationStatusQuery query =
        new GetNotificationStatusQuery(TenantId.of(tenantId), NotificationId.of(id));
    return getNotificationStatusUseCase.getStatus(query).map(NotificationStatusResponse::from);
  }

  private static SendNotificationCommand toCommand(
      final String tenantId, final SendNotificationRequest request) {
    return new SendNotificationCommand(
        TenantId.of(tenantId),
        ExternalId.of(request.externalId()),
        ChannelType.of(request.channelType()),
        RecipientId.of(request.recipientId()),
        Recipient.of(request.recipientAddress()),
        NotificationContent.of(request.subject(), request.body()),
        Priority.valueOf(request.priority()));
  }
}
