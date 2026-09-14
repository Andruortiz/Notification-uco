package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.GetNotificationStatusQuery;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.SearchNotificationsQuery;
import co.edu.uco.notification.core.port.in.SearchNotificationsUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

  private final SendNotificationUseCase sendNotificationUseCase;
  private final GetNotificationStatusUseCase getNotificationStatusUseCase;
  private final SearchNotificationsUseCase searchNotificationsUseCase;

  public NotificationController(
      final SendNotificationUseCase sendNotificationUseCase,
      final GetNotificationStatusUseCase getNotificationStatusUseCase,
      final SearchNotificationsUseCase searchNotificationsUseCase) {
    this.sendNotificationUseCase =
        Preconditions.requireNonNull(
            sendNotificationUseCase, "sendNotificationUseCase must not be null");
    this.getNotificationStatusUseCase =
        Preconditions.requireNonNull(
            getNotificationStatusUseCase, "getNotificationStatusUseCase must not be null");
    this.searchNotificationsUseCase =
        Preconditions.requireNonNull(
            searchNotificationsUseCase, "searchNotificationsUseCase must not be null");
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

  @GetMapping
  public Mono<NotificationSearchResponse> search(
      @RequestHeader("X-Tenant-Id") final String tenantId,
      @RequestParam(name = "recipientId", required = false) final String recipientId,
      @RequestParam(name = "channelType", required = false) final String channelType,
      @RequestParam(name = "status", required = false) final String status,
      @RequestParam(name = "from", required = false) final Instant from,
      @RequestParam(name = "to", required = false) final Instant to,
      @RequestParam(name = "limit", defaultValue = "50") final int limit,
      @RequestParam(name = "offset", defaultValue = "0") final int offset) {
    final SearchNotificationsQuery query =
        new SearchNotificationsQuery(
            TenantId.of(tenantId),
            recipientId == null ? null : RecipientId.of(recipientId),
            channelType == null ? null : ChannelType.of(channelType),
            status == null ? null : NotificationStatus.valueOf(status),
            from,
            to,
            limit,
            offset);
    return searchNotificationsUseCase.search(query).map(NotificationSearchResponse::from);
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
