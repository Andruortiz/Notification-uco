package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.GetNotificationStatusQuery;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.List;
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
    Preconditions.requireNonNull(query, "query must not be null");

    return notificationRepository
        .findById(query.notificationId())
        .filter(notification -> notification.tenantId().equals(query.tenantId()))
        .switchIfEmpty(Mono.error(new NotificationNotFoundException(query.notificationId())))
        .map(GetNotificationStatusService::toView);
  }

  private static NotificationStatusView toView(final Notification notification) {
    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    final DeliveryAttempt lastAttempt = attempts.isEmpty() ? null : attempts.getLast();
    final ProviderId lastProviderId = lastAttempt == null ? null : lastAttempt.providerId();
    final Instant lastUpdatedAt =
        lastAttempt == null ? notification.acceptedAt() : lastAttempt.occurredOn();

    return new NotificationStatusView(
        notification.notificationId(),
        notification.status(),
        notification.channelType(),
        lastProviderId,
        lastUpdatedAt);
  }
}
