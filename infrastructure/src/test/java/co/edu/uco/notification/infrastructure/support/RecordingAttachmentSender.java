package co.edu.uco.notification.infrastructure.support;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

public final class RecordingAttachmentSender implements NotificationSenderPort {

  private final ProviderId providerId;
  private final boolean supportsAttachments;
  private final List<Notification> received = new CopyOnWriteArrayList<>();
  private final Set<String> failOnce = ConcurrentHashMap.newKeySet();

  public RecordingAttachmentSender(final String providerId, final boolean supportsAttachments) {
    this.providerId = ProviderId.of(providerId);
    this.supportsAttachments = supportsAttachments;
  }

  @Override
  public Mono<AttemptResult> send(final Notification notification) {
    received.add(notification);
    return Mono.just(
        failOnce.remove(notification.externalId().value())
            ? AttemptResult.RECOVERABLE_FAILURE
            : AttemptResult.ACCEPTED);
  }

  @Override
  public ProviderId providerId() {
    return providerId;
  }

  @Override
  public Optional<String> disabledReason() {
    return Optional.empty();
  }

  @Override
  public boolean supportsAttachments() {
    return supportsAttachments;
  }

  public void failFirstAttemptOf(final String externalId) {
    failOnce.add(externalId);
  }

  public List<Notification> receivedFor(final NotificationId notificationId) {
    return received.stream()
        .filter(notification -> notification.notificationId().equals(notificationId))
        .toList();
  }
}
