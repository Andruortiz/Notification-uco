package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
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
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequeuePendingNotificationsServiceTest {

  private final NotificationMetricsPort metricsPort = Mockito.mock(NotificationMetricsPort.class);

  private final NotificationRepository notificationRepository =
      Mockito.mock(NotificationRepository.class);
  private final NotificationEventPublisherPort eventPublisherPort =
      Mockito.mock(NotificationEventPublisherPort.class);
  private final RetryPolicy retryPolicy = new RetryPolicy();

  private static final int BATCH_SIZE = 100;

  private final RequeuePendingNotificationsService service =
      new RequeuePendingNotificationsService(
          notificationRepository,
          eventPublisherPort,
          retryPolicy,
          Duration.ofSeconds(60),
          Duration.ofMinutes(10),
          BATCH_SIZE,
          metricsPort);

  @BeforeEach
  void stubEmptyByDefault() {
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.empty());
    when(notificationRepository.claimForRequeue(any(), anyInt())).thenReturn(Flux.empty());
    when(notificationRepository.claimStuckInProcess(any(), anyInt())).thenReturn(Flux.empty());
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
  void anUnexpectedErrorOnOneRecoverableNotificationDoesNotStopTheRestOfThePass() {
    final Notification broken =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    final Notification healthy =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(broken, healthy));
    when(notificationRepository.save(broken))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));
    when(notificationRepository.save(healthy)).thenReturn(Mono.just(healthy));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(healthy)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort).enqueueForDispatch(healthy);
  }

  @Test
  void aFailureReadingRecoverableNotificationsDoesNotPreventTheOrphanClaim() {
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.error(new IllegalStateException("mongo down")));
    final Notification orphaned = pendingNotificationWithAttempts(Instant.now().minusSeconds(120));
    when(notificationRepository.claimForRequeue(any(), anyInt())).thenReturn(Flux.just(orphaned));
    when(eventPublisherPort.enqueueForDispatch(orphaned)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort).enqueueForDispatch(orphaned);
  }

  @Test
  void aPendingNotificationWithPreviousAttemptsIsAnOrphanByPendingSince() {
    final Notification orphaned = pendingNotificationWithAttempts(Instant.now().minusSeconds(120));
    when(notificationRepository.claimForRequeue(any(), anyInt())).thenReturn(Flux.just(orphaned));
    when(eventPublisherPort.enqueueForDispatch(orphaned)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    assertEquals(NotificationStatus.PENDING, orphaned.status());
    verify(eventPublisherPort).enqueueForDispatch(orphaned);
    verify(notificationRepository, never()).save(any());
  }

  @Test
  void theOrphanClaimUsesTheThresholdAndTheBatchSize() {
    final Instant before = Instant.now();

    StepVerifier.create(service.requeuePending()).verifyComplete();

    final ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
    verify(notificationRepository).claimForRequeue(threshold.capture(), eq(BATCH_SIZE));
    assertFalse(threshold.getValue().isBefore(before.minusSeconds(60)));
    assertFalse(threshold.getValue().isAfter(Instant.now().minusSeconds(59)));
  }

  @Test
  void anEnqueueFailureIsIsolatedAndTheNotificationIsEnqueuedAgainInTheNextPass() {
    final Notification failing = pendingNotificationWithAttempts(Instant.now().minusSeconds(120));
    final Notification healthy = pendingNotificationWithAttempts(Instant.now().minusSeconds(120));
    when(notificationRepository.claimForRequeue(any(), anyInt()))
        .thenReturn(Flux.just(failing, healthy))
        .thenReturn(Flux.just(failing));
    when(eventPublisherPort.enqueueForDispatch(failing))
        .thenReturn(Mono.error(new IllegalStateException("broker down")))
        .thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(healthy)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();
    verify(eventPublisherPort).enqueueForDispatch(healthy);

    StepVerifier.create(service.requeuePending()).verifyComplete();
    verify(eventPublisherPort, times(2)).enqueueForDispatch(failing);
  }

  @Test
  void aFailingClaimDoesNotFailThePass() {
    when(notificationRepository.claimForRequeue(any(), anyInt()))
        .thenReturn(Flux.error(new IllegalStateException("mongo down")));
    when(notificationRepository.claimStuckInProcess(any(), anyInt()))
        .thenReturn(Flux.just(stuckInProcessNotification()));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(notificationRepository).claimStuckInProcess(any(), eq(BATCH_SIZE));
  }

  @Test
  void stuckInProcessNotificationsAreClaimedWithTheTimeoutAndTheBatchSize() {
    final Instant before = Instant.now();
    when(notificationRepository.claimStuckInProcess(any(), anyInt()))
        .thenReturn(Flux.just(stuckInProcessNotification()));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    final ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
    verify(notificationRepository).claimStuckInProcess(threshold.capture(), eq(BATCH_SIZE));
    assertFalse(threshold.getValue().isBefore(before.minus(Duration.ofMinutes(10))));
    assertFalse(threshold.getValue().isAfter(Instant.now().minus(Duration.ofMinutes(9))));
  }

  @Test
  void nothingIsEnqueuedNorSavedWhenThereIsNothingToRecover() {
    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort, never()).enqueueForDispatch(any());
    verify(notificationRepository, never()).save(any());
  }

  @Test
  void theRecoverablePassRespectsTheBatchSize() {
    final RequeuePendingNotificationsService smallBatch =
        new RequeuePendingNotificationsService(
            notificationRepository,
            eventPublisherPort,
            retryPolicy,
            Duration.ofSeconds(60),
            Duration.ofMinutes(10),
            2,
            metricsPort);
    final List<Notification> due = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      final Notification notification =
          recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
      due.add(notification);
      when(notificationRepository.save(notification)).thenReturn(Mono.just(notification));
      when(eventPublisherPort.enqueueForDispatch(notification)).thenReturn(Mono.empty());
    }
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.fromIterable(due));

    StepVerifier.create(smallBatch.requeuePending()).verifyComplete();

    verify(eventPublisherPort, times(2)).enqueueForDispatch(any());
    verify(notificationRepository).claimForRequeue(any(), eq(2));
    verify(notificationRepository).claimStuckInProcess(any(), eq(2));
  }

  @Test
  void theBackoffIsCalculatedOnTheCurrentCycleOnly() {
    final Instant now = Instant.now();
    final List<DeliveryAttempt> attempts = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      attempts.add(
          DeliveryAttempt.of(
              now.minusSeconds(3600),
              AttemptResult.RECOVERABLE_FAILURE,
              AttemptOrigin.AUTOMATIC,
              ProviderId.of("brevo"),
              1));
    }
    attempts.add(
        DeliveryAttempt.of(
            now.minusSeconds(60),
            AttemptResult.RECOVERABLE_FAILURE,
            AttemptOrigin.AUTOMATIC,
            ProviderId.of("brevo"),
            2));
    final Notification afterManualRetry =
        reconstituteRecoverable(
            new NotificationMetadata(now.minusSeconds(7200), 1L, null, null, 2), attempts);
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(afterManualRetry));
    when(notificationRepository.save(afterManualRetry)).thenReturn(Mono.just(afterManualRetry));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(afterManualRetry)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort).enqueueForDispatch(afterManualRetry);
  }

  @Test
  void aRecoverableNotificationWithoutAttemptsIsDueImmediately() {
    final Notification released =
        reconstituteRecoverable(
            new NotificationMetadata(Instant.now().minusSeconds(900), 1L), List.of());
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(released));
    when(notificationRepository.save(released)).thenReturn(Mono.just(released));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(released)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort).enqueueForDispatch(released);
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
  void requeueEventsCarryThePersistedCorrelationIdOfTheNotification() {
    final CorrelationId persisted = CorrelationId.of("persisted-corr");
    final Notification due =
        Notification.reconstitute(
            NotificationId.newId(),
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-1"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            NotificationStatus.RECOVERABLE,
            new NotificationMetadata(Instant.now().minusSeconds(300), 1L),
            List.of(
                DeliveryAttempt.of(
                    Instant.now().minusSeconds(120),
                    AttemptResult.RECOVERABLE_FAILURE,
                    AttemptOrigin.AUTOMATIC,
                    ProviderId.of("brevo"))),
            persisted);
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(due));
    when(notificationRepository.save(due)).thenReturn(Mono.just(due));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(due)).thenReturn(Mono.empty());

    StepVerifier.create(
            service.requeuePending().contextWrite(c -> c.put("correlationId", "sched-x")))
        .verifyComplete();

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
    verify(eventPublisherPort).publish(events.capture());
    assertEquals(persisted, events.getValue().getFirst().correlationId());
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

  private static Notification reconstituteRecoverable(
      final NotificationMetadata metadata, final List<DeliveryAttempt> attempts) {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-1"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        NotificationStatus.RECOVERABLE,
        metadata,
        attempts);
  }

  private static Notification pendingNotificationWithAttempts(final Instant pendingSince) {
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
        new NotificationMetadata(pendingSince.minusSeconds(300), 2L, pendingSince, null, 1),
        List.of(
            DeliveryAttempt.of(
                pendingSince.minusSeconds(200),
                AttemptResult.RECOVERABLE_FAILURE,
                AttemptOrigin.AUTOMATIC,
                ProviderId.of("brevo"))));
  }

  private static Notification stuckInProcessNotification() {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-3"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        NotificationStatus.RECOVERABLE,
        new NotificationMetadata(
            Instant.now().minusSeconds(1800), 3L, Instant.now().minusSeconds(1700), null, 1),
        List.of());
  }

  @Test
  void aFailedRequeueIsReportedWithItsErrorCode() {
    final Notification broken =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(broken));
    when(notificationRepository.save(broken))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));

    assertReported("REQUEUE_FAILED", ErrorCode.REQUEUE_FAILED);
  }

  @Test
  void aFailedEnqueueIsReportedWithItsErrorCode() {
    final Notification failing = pendingNotificationWithAttempts(Instant.now().minusSeconds(120));
    when(notificationRepository.claimForRequeue(any(), anyInt())).thenReturn(Flux.just(failing));
    when(eventPublisherPort.enqueueForDispatch(failing))
        .thenReturn(Mono.error(new IllegalStateException("broker down")));

    assertReported("ENQUEUE_FAILED", ErrorCode.ENQUEUE_FAILED);
  }

  @Test
  void aFailedPassIsReportedWithItsErrorCode() {
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.error(new IllegalStateException("mongo down")));

    assertReported("REQUEUE_RECOVERABLE_PASS_FAILED", ErrorCode.REQUEUE_PASS_FAILED);
  }

  @Test
  void aReleasedStuckNotificationIsReportedWithItsErrorCode() {
    when(notificationRepository.claimStuckInProcess(any(), anyInt()))
        .thenReturn(Flux.just(stuckInProcessNotification()));

    assertReported(
        "DISPATCH_STUCK_IN_PROCESS_RELEASED", ErrorCode.DISPATCH_STUCK_IN_PROCESS_RELEASED);
  }

  private void assertReported(final String marker, final ErrorCode code) {
    try (LogCapture logs = new LogCapture(RequeuePendingNotificationsService.class)) {
      StepVerifier.create(service.requeuePending()).verifyComplete();

      final List<java.util.logging.LogRecord> matching =
          logs.records().stream().filter(r -> r.getMessage().contains(marker)).toList();
      assertEquals(1, matching.size());
      assertTrue(matching.get(0).getMessage().contains("errorCode=" + code.format()));
      assertFalse(matching.get(0).getMessage().contains("category="));
    }
  }

  @Test
  void aFailedRequeueCountsOneErrorWithItsCode() {
    final Notification broken =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(broken));
    when(notificationRepository.save(broken))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(metricsPort, times(1)).errorRecorded(ErrorCode.REQUEUE_FAILED);
    verify(metricsPort, times(1)).errorRecorded(any());
  }

  @Test
  void aFailedEnqueueCountsOneErrorWithItsCode() {
    final Notification failing = pendingNotificationWithAttempts(Instant.now().minusSeconds(120));
    when(notificationRepository.claimForRequeue(any(), anyInt())).thenReturn(Flux.just(failing));
    when(eventPublisherPort.enqueueForDispatch(failing))
        .thenReturn(Mono.error(new IllegalStateException("broker down")));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(metricsPort, times(1)).errorRecorded(ErrorCode.ENQUEUE_FAILED);
  }

  @Test
  void aFailedPassCountsOneErrorWithItsCode() {
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.error(new IllegalStateException("mongo down")));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(metricsPort, times(1)).errorRecorded(ErrorCode.REQUEUE_PASS_FAILED);
  }

  @Test
  void aReleasedStuckNotificationCountsOneErrorWithItsCode() {
    when(notificationRepository.claimStuckInProcess(any(), anyInt()))
        .thenReturn(Flux.just(stuckInProcessNotification()));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(metricsPort, times(1)).errorRecorded(ErrorCode.DISPATCH_STUCK_IN_PROCESS_RELEASED);
  }

  @Test
  void aCleanPassCountsNoError() {
    final Notification dueNotification =
        recoverableNotification(NotificationId.newId(), Instant.now().minusSeconds(120));
    when(notificationRepository.findByStatus(NotificationStatus.RECOVERABLE))
        .thenReturn(Flux.just(dueNotification));
    when(notificationRepository.save(dueNotification)).thenReturn(Mono.just(dueNotification));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(dueNotification)).thenReturn(Mono.empty());

    StepVerifier.create(service.requeuePending()).verifyComplete();

    Mockito.verifyNoInteractions(metricsPort);
  }

  @Test
  void constructorRejectsNullMetricsPort() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () ->
            new RequeuePendingNotificationsService(
                notificationRepository,
                eventPublisherPort,
                retryPolicy,
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                BATCH_SIZE,
                null));
  }
}
