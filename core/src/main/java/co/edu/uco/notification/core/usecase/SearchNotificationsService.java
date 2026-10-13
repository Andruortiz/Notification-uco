package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.port.in.NotificationSearchPage;
import co.edu.uco.notification.core.port.in.NotificationSearchResult;
import co.edu.uco.notification.core.port.in.SearchNotificationsQuery;
import co.edu.uco.notification.core.port.in.SearchNotificationsUseCase;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import reactor.core.publisher.Mono;

public final class SearchNotificationsService implements SearchNotificationsUseCase {

  private final NotificationRepository notificationRepository;

  public SearchNotificationsService(final NotificationRepository notificationRepository) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
  }

  @Override
  public Mono<NotificationSearchPage> search(final SearchNotificationsQuery query) {
    Preconditions.requireNonNull(query, "query must not be null");
    final NotificationSearchCriteria criteria =
        new NotificationSearchCriteria(
            query.tenantId(),
            query.recipientId(),
            query.channelType(),
            query.status(),
            query.from(),
            query.to(),
            query.limit(),
            query.offset());

    return notificationRepository
        .search(criteria)
        .collectList()
        .map(notifications -> toPage(notifications, query.limit(), query.offset()));
  }

  private static NotificationSearchPage toPage(
      final List<Notification> notifications, final int limit, final int offset) {
    final boolean hasNext = notifications.size() > limit;
    final List<Notification> page = hasNext ? notifications.subList(0, limit) : notifications;
    final List<NotificationSearchResult> items =
        page.stream().map(SearchNotificationsService::toResult).toList();
    return new NotificationSearchPage(items, limit, offset, hasNext);
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
