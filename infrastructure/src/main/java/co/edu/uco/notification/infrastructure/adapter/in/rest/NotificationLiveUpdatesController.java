package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.SubscribeToNotificationUpdatesQuery;
import co.edu.uco.notification.core.port.in.SubscribeToNotificationUpdatesUseCase;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class NotificationLiveUpdatesController {

  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

  private final SubscribeToNotificationUpdatesUseCase subscribeToNotificationUpdatesUseCase;

  public NotificationLiveUpdatesController(
      final SubscribeToNotificationUpdatesUseCase subscribeToNotificationUpdatesUseCase) {
    this.subscribeToNotificationUpdatesUseCase =
        Preconditions.requireNonNull(
            subscribeToNotificationUpdatesUseCase,
            "subscribeToNotificationUpdatesUseCase must not be null");
  }

  @GetMapping(value = "/notifications:subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<NotificationLiveUpdateResponse>> subscribe(
      @RequestHeader("X-Tenant-Id") final String tenantId,
      @RequestParam(name = "recipientId", required = false) final String recipientId,
      @RequestParam(name = "channelType", required = false) final String channelType,
      @RequestParam(name = "status", required = false) final String status,
      @RequestParam(name = "from", required = false) final Instant from,
      @RequestParam(name = "to", required = false) final Instant to) {
    final SubscribeToNotificationUpdatesQuery query =
        new SubscribeToNotificationUpdatesQuery(
            TenantId.of(tenantId),
            recipientId == null ? null : RecipientId.of(recipientId),
            channelType == null ? null : ChannelType.of(channelType),
            status == null ? null : NotificationStatus.valueOf(status),
            from,
            to);

    final Flux<ServerSentEvent<NotificationLiveUpdateResponse>> updates =
        subscribeToNotificationUpdatesUseCase
            .subscribe(query)
            .map(NotificationLiveUpdateResponse::from)
            .map(payload -> ServerSentEvent.builder(payload).event("update").build());

    return Flux.merge(updates, heartbeat());
  }

  private static Flux<ServerSentEvent<NotificationLiveUpdateResponse>> heartbeat() {
    return Flux.interval(HEARTBEAT_INTERVAL)
        .map(
            tick ->
                ServerSentEvent.<NotificationLiveUpdateResponse>builder()
                    .comment("keep-alive")
                    .build());
  }
}
