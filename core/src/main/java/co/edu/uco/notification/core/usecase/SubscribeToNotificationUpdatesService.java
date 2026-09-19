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
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

public final class SubscribeToNotificationUpdatesService
    implements SubscribeToNotificationUpdatesUseCase {

  private static final int INITIAL_SNAPSHOT_LIMIT = 200;
  private static final int LIVE_UPDATE_BUFFER_LIMIT = 256;

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

    return Flux.defer(
        () -> {
          final ConnectableFlux<NotificationLiveUpdate> liveUpdates =
              notificationUpdatesPort
                  .updates()
                  .flatMap(notificationRepository::findById)
                  .filter(notification -> query.tenantId().equals(notification.tenantId()))
                  .map(notification -> toLiveUpdate(notification, criteria))
                  .replay(LIVE_UPDATE_BUFFER_LIMIT);
          final Disposable connection = liveUpdates.connect();
          return Flux.concat(snapshot(criteria), liveUpdates)
              .doFinally(signal -> connection.dispose());
        });
  }

  private Flux<NotificationLiveUpdate> snapshot(final NotificationSearchCriteria criteria) {
    return notificationRepository
        .search(criteria)
        .map(SubscribeToNotificationUpdatesService::toUpsert);
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
