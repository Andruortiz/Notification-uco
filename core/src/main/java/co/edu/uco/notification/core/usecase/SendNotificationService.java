package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.ContentSchemaValidator;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import reactor.core.publisher.Mono;

public final class SendNotificationService implements SendNotificationUseCase {

  private final ChannelCatalogPort channelCatalogPort;
  private final NotificationRepository notificationRepository;
  private final NotificationEventPublisherPort eventPublisherPort;

  public SendNotificationService(
      final ChannelCatalogPort channelCatalogPort,
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort) {
    this.channelCatalogPort =
        Preconditions.requireNonNull(channelCatalogPort, "channelCatalogPort must not be null");
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.eventPublisherPort =
        Preconditions.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
  }

  @Override
  public Mono<SendNotificationResult> send(final SendNotificationCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");

    return channelCatalogPort
        .findActiveRoute(command.channelType(), command.tenantId())
        .switchIfEmpty(Mono.error(new ChannelNotAvailableException(command.channelType())))
        .flatMap(route -> validateAndProceed(route, command));
  }

  private Mono<SendNotificationResult> validateAndProceed(
      final ChannelRoute route, final SendNotificationCommand command) {
    ContentSchemaValidator.validate(route.channelType(), route.contentSchema(), command.content());
    return notificationRepository
        .findByTenantAndExternalId(command.tenantId(), command.externalId())
        .map(existing -> toResult(existing, true))
        .switchIfEmpty(Mono.defer(() -> acceptAndDispatch(command)));
  }

  private Mono<SendNotificationResult> acceptAndDispatch(final SendNotificationCommand command) {
    final Notification notification =
        Notification.accept(
            command.tenantId(),
            command.externalId(),
            command.channelType(),
            command.recipientId(),
            command.recipient(),
            command.content(),
            command.priority());
    final List<DomainEvent> events = notification.pullEvents();

    return notificationRepository
        .save(notification)
        .flatMap(
            saved ->
                eventPublisherPort
                    .publish(events)
                    .then(eventPublisherPort.enqueueForDispatch(saved))
                    .thenReturn(toResult(saved, false)));
  }

  private static SendNotificationResult toResult(
      final Notification notification, final boolean duplicate) {
    return new SendNotificationResult(
        notification.notificationId(), notification.status(), duplicate);
  }
}
