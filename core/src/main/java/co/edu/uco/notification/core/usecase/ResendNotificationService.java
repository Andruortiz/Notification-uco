package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.exception.NotificationAlreadyAcceptedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationStateNotAllowedException;
import co.edu.uco.notification.core.exception.ResendChannelInvalidException;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.ResendNotificationCommand;
import co.edu.uco.notification.core.port.in.ResendNotificationUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class ResendNotificationService implements ResendNotificationUseCase {

  static final int MAX_EXTERNAL_ID_LENGTH = 255;

  private final NotificationRepository notificationRepository;
  private final SendNotificationUseCase sendNotificationUseCase;

  public ResendNotificationService(
      final NotificationRepository notificationRepository,
      final SendNotificationUseCase sendNotificationUseCase) {
    this.notificationRepository =
        Preconditions.requireNonNull(
            notificationRepository, "notificationRepository must not be null");
    this.sendNotificationUseCase =
        Preconditions.requireNonNull(
            sendNotificationUseCase, "sendNotificationUseCase must not be null");
  }

  @Override
  public Mono<NotificationStatusView> resend(final ResendNotificationCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");

    return notificationRepository
        .findById(command.notificationId())
        .filter(notification -> notification.tenantId().equals(command.tenantId()))
        .switchIfEmpty(Mono.error(new NotificationNotFoundException(command.notificationId())))
        .flatMap(original -> sendCopy(original, command))
        .flatMap(result -> notificationRepository.findById(result.notificationId()))
        .map(NotificationStatusView::from);
  }

  private Mono<SendNotificationResult> sendCopy(
      final Notification original, final ResendNotificationCommand command) {
    if (original.status() != NotificationStatus.FAILED) {
      return Mono.error(new NotificationStateNotAllowedException(original.status()));
    }
    if (original.channelType().equals(command.channelType())) {
      return Mono.error(
          new ResendChannelInvalidException("The resend channel must differ from the original"));
    }
    if (original.content().hasAttachments()) {
      return Mono.error(
          new ResendChannelInvalidException("A notification with attachments cannot be resent"));
    }
    final ExternalId derived = derivedExternalId(original, command);
    if (derived.value().length() > MAX_EXTERNAL_ID_LENGTH) {
      return Mono.error(
          new ResendChannelInvalidException("The derived externalId exceeds the maximum length"));
    }
    return sendNotificationUseCase
        .send(
            new SendNotificationCommand(
                command.tenantId(),
                derived,
                command.channelType(),
                original.recipientId(),
                command.recipient(),
                original.content(),
                original.priority(),
                null,
                command.correlationId()))
        .flatMap(
            result ->
                result.duplicate()
                    ? Mono.error(
                        new NotificationAlreadyAcceptedException(command.tenantId(), derived))
                    : Mono.just(result));
  }

  private static ExternalId derivedExternalId(
      final Notification original, final ResendNotificationCommand command) {
    return ExternalId.of(
        original.externalId().value() + "-resend-" + command.channelType().value());
  }
}
