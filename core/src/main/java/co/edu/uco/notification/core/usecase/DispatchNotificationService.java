package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttemptOrigin;
import co.edu.uco.notification.core.domain.AttemptResult;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.ProviderId;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import reactor.core.publisher.Mono;

public final class DispatchNotificationService implements DispatchNotificationUseCase {

  private final NotificationRepository notificationRepository;
  private final ChannelCatalogPort channelCatalogPort;
  private final NotificationSenderPort notificationSenderPort;
  private final NotificationEventPublisherPort eventPublisherPort;

  public DispatchNotificationService(
      final NotificationRepository notificationRepository,
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderPort notificationSenderPort,
      final NotificationEventPublisherPort eventPublisherPort) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.channelCatalogPort =
        Preconditions.requireNonNull(channelCatalogPort, "channelCatalogPort must not be null");
    this.notificationSenderPort =
        Preconditions.requireNonNull(
            notificationSenderPort, "notificationSenderPort must not be null");
    this.eventPublisherPort =
        Preconditions.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
  }

  @Override
  public Mono<Void> dispatch(final NotificationId notificationId) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");

    return notificationRepository
        .findById(notificationId)
        .switchIfEmpty(Mono.error(new NotificationNotFoundException(notificationId)))
        .flatMap(this::dispatchNotification);
  }

  private Mono<Void> dispatchNotification(final Notification notification) {
    return channelCatalogPort
        .findActiveRoute(notification.channelType(), notification.tenantId())
        .switchIfEmpty(Mono.error(new ChannelNotAvailableException(notification.channelType())))
        .flatMap(route -> attemptSend(notification, route));
  }

  private Mono<Void> attemptSend(final Notification notification, final ChannelRoute route) {
    notification.markQueued();

    return notificationSenderPort
        .send(notification)
        .flatMap(
            outcome ->
                saveAndPublish(applyOutcome(notification, outcome, route.preferredProvider())));
  }

  private Mono<Void> saveAndPublish(final Notification notification) {
    final List<DomainEvent> events = notification.pullEvents();

    return notificationRepository
        .save(notification)
        .flatMap(saved -> eventPublisherPort.publish(events))
        .then();
  }

  private static Notification applyOutcome(
      final Notification notification, final AttemptResult outcome, final ProviderId providerId) {
    if (outcome == AttemptResult.ACCEPTED) {
      notification.markDelivered(AttemptOrigin.AUTOMATIC, providerId);
    } else if (outcome == AttemptResult.RECOVERABLE_FAILURE) {
      notification.markRecoverable(AttemptOrigin.AUTOMATIC, providerId);
    } else {
      notification.markFailed(AttemptOrigin.AUTOMATIC, providerId);
    }
    return notification;
  }
}
