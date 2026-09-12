package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationMetadata;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequeuePendingNotificationsServiceTest {

  private final NotificationRepository notificationRepository =
      Mockito.mock(NotificationRepository.class);
  private final NotificationEventPublisherPort eventPublisherPort =
      Mockito.mock(NotificationEventPublisherPort.class);
  private final RetryPolicy retryPolicy = new RetryPolicy();

  private final RequeuePendingNotificationsService service =
      new RequeuePendingNotificationsService(
          notificationRepository, eventPublisherPort, retryPolicy, Duration.ofSeconds(60));

  @BeforeEach
  void stubEmptyByDefault() {
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.empty());
    when(notificationRepository.findByStatus(NotificationStatus.PENDING)).thenReturn(Flux.empty());
  }

  @Test
  void requeuesNotificationsWhoseBackoffAlreadyElapsed() {
    final Notification dueNotification =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(dueNotification));
    when(notificationRepository.save(dueNotification)).thenReturn(Mono.just(dueNotification));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(dueNotification)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    assertEquals(NotificationStatus.PENDING, dueNotification.status());
    verify(notificationRepository).save(dueNotification);
    verify(eventPublisherPort).enqueueForDispatch(dueNotification);
  }

  @Test
  void doesNotTouchNotificationsWhoseBackoffHasNotElapsedYet() {
    final Notification notDueNotification =
        recoverableNotification(NotificationId.newId(), Instant.now());
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(notDueNotification));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    assertEquals(NotificationStatus.RECOVERABLE, notDueNotification.status());
    verify(notificationRepository, never()).save(any());
  }

  @Test
  void completesWithoutErrorWhenThereIsNothingToRequeue() {
    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(notificationRepository, never()).save(any());
  }

  @Test
  void aVersionConflictOnOneNotificationDoesNotStopTheRestOfTheBatch() {
    final Notification conflicting =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    final Notification healthy =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(conflicting, healthy));
    when(notificationRepository.save(conflicting))
        .thenReturn(
            Mono.error(new NotificationVersionConflictException(conflicting.notificationId())));
    when(notificationRepository.save(healthy)).thenReturn(Mono.just(healthy));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(healthy)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(notificationRepository).save(healthy);
    verify(eventPublisherPort).enqueueForDispatch(healthy);
  }

  @Test
  void reenqueuesPendingNotificationsOrphanedBeforeTheThresholdWithoutChangingState() {
    final Notification orphaned =
        pendingNotificationWithoutAttempts(Instant.now().minusSeconds(120));
    when(notificationRepository.findByStatus(NotificationStatus.PENDING))
        .thenReturn(Flux.just(orphaned));
    when(eventPublisherPort.enqueueForDispatch(orphaned)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    assertEquals(NotificationStatus.PENDING, orphaned.status());
    verify(notificationRepository, never()).save(any());
    verify(eventPublisherPort).enqueueForDispatch(orphaned);
  }

  @Test
  void doesNotTouchPendingNotificationsAcceptedMoreRecentlyThanTheThreshold() {
    final Notification recentlyAccepted = pendingNotificationWithoutAttempts(Instant.now());
    when(notificationRepository.findByStatus(NotificationStatus.PENDING))
        .thenReturn(Flux.just(recentlyAccepted));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort, never()).enqueueForDispatch(any());
  }

  @Test
  void doesNotTouchPendingNotificationsThatAlreadyHaveADeliveryAttempt() {
    final Notification requeuedFromRecoverable =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    requeuedFromRecoverable.requeue();
    when(notificationRepository.findByStatus(NotificationStatus.PENDING))
        .thenReturn(Flux.just(requeuedFromRecoverable));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort, never()).enqueueForDispatch(any());
  }

  private static Notification recoverableNotification(
      final NotificationId id, final Instant lastAttemptAt) {
    final List<DeliveryAttempt> attempts =
        List.of(
            DeliveryAttempt.of(
                lastAttemptAt,
                AttemptResult.RECOVERABLE_FAILURE,
                AttemptOrigin.AUTOMATIC,
                ProviderId.of("brevo")));
    return Notification.reconstitute(
        id,
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-1"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        NotificationStatus.RECOVERABLE,
        new NotificationMetadata(Instant.now().minusSeconds(300), 1L),
        attempts);
  }

  private static Notification pendingNotificationWithoutAttempts(final Instant acceptedAt) {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-2"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        NotificationStatus.PENDING,
        new NotificationMetadata(acceptedAt, null),
        List.of());
  }
}
