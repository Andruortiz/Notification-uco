package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.port.in.LiveUpdateAction;
import co.edu.uco.notification.core.port.in.NotificationLiveUpdate;
import co.edu.uco.notification.core.port.in.NotificationSearchResult;
import co.edu.uco.notification.core.port.in.SubscribeToNotificationUpdatesQuery;
import co.edu.uco.notification.core.port.in.SubscribeToNotificationUpdatesUseCase;
import co.edu.uco.notification.core.port.out.NotificationUpdatesPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Flux;

public final class SubscribeToNotificationUpdatesService
    implements SubscribeToNotificationUpdatesUseCase {

  private static final int INITIAL_SNAPSHOT_LIMIT = 200;

  private final NotificationRepository notificationRepository;
  private final NotificationUpdatesPort notificationUpdatesPort;

  public SubscribeToNotificationUpdatesService(
      final NotificationRepository notificationRepository,
      final NotificationUpdatesPort notificationUpdatesPort) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.notificationUpdatesPort =
        Preconditions.requireNonNull(
            notificationUpdatesPort, "notificationUpdatesPort must not be null");
  }

  @Override
  public Flux<NotificationLiveUpdate> subscribe(final SubscribeToNotificationUpdatesQuery query) {
    Preconditions.requireNonNull(query, "query must not be null");
    final NotificationSearchCriteria criteria = toCriteria(query);

    final Flux<NotificationLiveUpdate> snapshot =
        notificationRepository
            .search(criteria)
            .map(SubscribeToNotificationUpdatesService::toUpsert);

    final Flux<NotificationLiveUpdate> liveUpdates =
        notificationUpdatesPort
            .updates()
            .flatMap(notificationRepository::findById)
            .map(notification -> toLiveUpdate(notification, criteria));

    return Flux.concat(snapshot, liveUpdates);
  }

  private static NotificationSearchCriteria toCriteria(
      final SubscribeToNotificationUpdatesQuery query) {
    return new NotificationSearchCriteria(
        query.tenantId(),
        query.recipientId(),
        query.channelType(),
        query.status(),
        query.from(),
        query.to(),
        INITIAL_SNAPSHOT_LIMIT,
        0);
  }

  private static NotificationLiveUpdate toUpsert(final Notification notification) {
    return new NotificationLiveUpdate(toResult(notification), LiveUpdateAction.UPSERT);
  }

  private static NotificationLiveUpdate toLiveUpdate(
      final Notification notification, final NotificationSearchCriteria criteria) {
    final LiveUpdateAction action =
        criteria.matches(notification) ? LiveUpdateAction.UPSERT : LiveUpdateAction.REMOVE;
    return new NotificationLiveUpdate(toResult(notification), action);
  }

  private static NotificationSearchResult toResult(final Notification notification) {
    return new NotificationSearchResult(
        notification.notificationId(),
        notification.externalId(),
        notification.recipientId(),
        notification.channelType(),
        notification.status(),
        notification.acceptedAt(),
        notification.deliveryAttempts());
  }
}
