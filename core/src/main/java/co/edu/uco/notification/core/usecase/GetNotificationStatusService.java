package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.GetNotificationStatusQuery;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.NotificationTimelineView;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Comparator;
import reactor.core.publisher.Mono;

public final class GetNotificationStatusService implements GetNotificationStatusUseCase {

  private final NotificationRepository notificationRepository;

  public GetNotificationStatusService(final NotificationRepository notificationRepository) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
  }

  @Override
  public Mono<NotificationStatusView> getStatus(final GetNotificationStatusQuery query) {
    return findOwned(query).map(NotificationStatusView::from);
  }

  @Override
  public Mono<NotificationTimelineView> getTimeline(final GetNotificationStatusQuery query) {
    return findOwned(query).map(GetNotificationStatusService::toTimeline);
  }

  private Mono<Notification> findOwned(final GetNotificationStatusQuery query) {
    Preconditions.requireNonNull(query, "query must not be null");

    return notificationRepository
        .findById(query.notificationId())
        .filter(notification -> notification.tenantId().equals(query.tenantId()))
        .switchIfEmpty(Mono.error(new NotificationNotFoundException(query.notificationId())));
  }

  private static NotificationTimelineView toTimeline(final Notification notification) {
    return new NotificationTimelineView(
        notification.notificationId(),
        notification.status(),
        notification.channelType(),
        notification.recipient(),
        notification.correlationId(),
        notification.deliveryAttempts().stream()
            .sorted(Comparator.comparing(DeliveryAttempt::occurredOn))
            .toList());
  }
}
