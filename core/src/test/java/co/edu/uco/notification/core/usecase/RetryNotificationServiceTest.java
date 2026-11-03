package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
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
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationStateNotAllowedException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.port.in.RetryNotificationCommand;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RetryNotificationServiceTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");

  private final NotificationRepository repository = Mockito.mock(NotificationRepository.class);
  private final NotificationEventPublisherPort publisher =
      Mockito.mock(NotificationEventPublisherPort.class);
  private RetryNotificationService service;

  @BeforeEach
  void setUp() {
    service = new RetryNotificationService(repository, publisher);
    when(publisher.publish(any())).thenReturn(Mono.empty());
    when(publisher.enqueueForDispatch(any(), any())).thenReturn(Mono.empty());
  }

  private static DeliveryAttempt recoverableAttempt() {
    return DeliveryAttempt.of(
        Instant.now(),
        AttemptResult.RECOVERABLE_FAILURE,
        AttemptOrigin.AUTOMATIC,
        ProviderId.of("brevo"));
  }

  private static Notification notificationIn(
      final NotificationStatus status, final List<DeliveryAttempt> attempts) {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TENANT,
            ExternalId.of("order-1"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        status,
        new NotificationMetadata(Instant.now(), 3L),
        attempts);
  }

  private void stubFound(final Notification notification) {
    when(repository.findById(notification.notificationId())).thenReturn(Mono.just(notification));
    when(repository.save(notification)).thenReturn(Mono.just(notification));
  }

  @ParameterizedTest
  @EnumSource(
      value = NotificationStatus.class,
      names = {"FAILED", "RECOVERABLE"})
  void aFailedOrRecoverableNotificationGoesBackToPendingAndIsQueuedAsManual(
      final NotificationStatus status) {
    final Notification notification = notificationIn(status, List.of());
    stubFound(notification);

    StepVerifier.create(
            service.retry(new RetryNotificationCommand(TENANT, notification.notificationId())))
        .assertNext(view -> assertEquals(NotificationStatus.PENDING, view.status()))
        .verifyComplete();

    verify(repository).save(notification);
    verify(publisher).enqueueForDispatch(notification, AttemptOrigin.MANUAL);
  }

  @ParameterizedTest
  @EnumSource(
      value = NotificationStatus.class,
      names = {"PENDING", "IN_PROCESS", "DELIVERED", "DISCARDED"})
  void anyOtherStateIsRejectedWithoutSavingOrQueueing(final NotificationStatus status) {
    final Notification notification = notificationIn(status, List.of());
    when(repository.findById(notification.notificationId())).thenReturn(Mono.just(notification));

    StepVerifier.create(
            service.retry(new RetryNotificationCommand(TENANT, notification.notificationId())))
        .expectError(NotificationStateNotAllowedException.class)
        .verify();

    assertEquals(status, notification.status());
    verify(repository, never()).save(any());
    verify(publisher, never()).enqueueForDispatch(any(), any());
  }

  @Test
  void aForeignAndAnUnknownNotificationGiveTheSameNotFoundWithoutEffects() {
    final Notification foreign = notificationIn(NotificationStatus.FAILED, List.of());
    when(repository.findById(foreign.notificationId())).thenReturn(Mono.just(foreign));
    final NotificationId unknown = NotificationId.newId();
    when(repository.findById(unknown)).thenReturn(Mono.empty());

    StepVerifier.create(
            service.retry(
                new RetryNotificationCommand(TenantId.of("tenant-2"), foreign.notificationId())))
        .expectError(NotificationNotFoundException.class)
        .verify();
    StepVerifier.create(
            service.retry(new RetryNotificationCommand(TenantId.of("tenant-2"), unknown)))
        .expectError(NotificationNotFoundException.class)
        .verify();

    assertEquals(NotificationStatus.FAILED, foreign.status());
    verify(repository, never()).save(any());
    verify(publisher, never()).enqueueForDispatch(any(), any());
  }

  @Test
  void aLostVersionRaceIsPropagatedAndNothingIsQueued() {
    final Notification notification = notificationIn(NotificationStatus.FAILED, List.of());
    when(repository.findById(notification.notificationId())).thenReturn(Mono.just(notification));
    when(repository.save(notification))
        .thenReturn(
            Mono.error(new NotificationVersionConflictException(notification.notificationId())));

    StepVerifier.create(
            service.retry(new RetryNotificationCommand(TENANT, notification.notificationId())))
        .expectError(NotificationVersionConflictException.class)
        .verify();

    verify(publisher, never()).enqueueForDispatch(any(), any());
  }

  @Test
  void theRetryDoesNotResetTheAttemptBudget() {
    final Notification notification =
        notificationIn(
            NotificationStatus.FAILED,
            List.of(
                recoverableAttempt(),
                recoverableAttempt(),
                recoverableAttempt(),
                recoverableAttempt(),
                recoverableAttempt()));
    stubFound(notification);

    StepVerifier.create(
            service.retry(new RetryNotificationCommand(TENANT, notification.notificationId())))
        .expectNextCount(1)
        .verifyComplete();

    assertEquals(5, notification.recoverableAttemptsInCurrentCycle());
    assertEquals(1, notification.currentCycle());
    assertEquals(5, notification.deliveryAttempts().size());
  }
}
