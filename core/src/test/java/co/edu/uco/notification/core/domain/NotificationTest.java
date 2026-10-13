package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.event.NotificationAccepted;
import co.edu.uco.notification.core.domain.event.NotificationDelivered;
import co.edu.uco.notification.core.domain.event.NotificationDiscarded;
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.event.NotificationQueued;
import co.edu.uco.notification.core.domain.event.NotificationRecoverable;
import co.edu.uco.notification.core.domain.event.NotificationRequeued;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.exception.InvalidStatusTransitionException;
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationTest {

  private static Notification accepted() {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL));
  }

  @Test
  void acceptWithCorrelationIdStoresItAndStampsEveryEvent() {
    final CorrelationId correlationId = CorrelationId.of("corr-42");
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL),
            correlationId);
    notification.markQueued();
    notification.markRecoverable(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));
    notification.requeue();
    notification.markQueued();
    notification.markDelivered(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    final List<DomainEvent> events = notification.pullEvents();

    assertEquals(correlationId, notification.correlationId());
    assertEquals(6, events.size());
    events.forEach(
        event -> {
          assertEquals(correlationId, event.correlationId());
          assertEquals(TenantId.of("tenant-1"), event.tenantId());
          assertEquals(notification.notificationId(), event.notificationId());
        });
  }

  @Test
  void failureAndDiscardEventsCarryThePersistedCorrelationId() {
    final CorrelationId correlationId = CorrelationId.of("corr-43");
    final Notification failed = acceptedWith(correlationId);
    failed.markQueued();
    failed.markFailed(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));
    final Notification exhausted = acceptedWith(correlationId);
    exhausted.markQueued();
    exhausted.markRetriesExhausted(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));
    final Notification discarded = acceptedWith(correlationId);
    discarded.discard();

    failed.pullEvents().forEach(event -> assertEquals(correlationId, event.correlationId()));
    exhausted.pullEvents().forEach(event -> assertEquals(correlationId, event.correlationId()));
    discarded.pullEvents().forEach(event -> assertEquals(correlationId, event.correlationId()));
  }

  @Test
  void acceptWithoutCorrelationIdLeavesItNull() {
    assertNull(accepted().correlationId());
  }

  private static Notification acceptedWith(final CorrelationId correlationId) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL),
        correlationId);
  }

  @Test
  void acceptCreatesNotificationInPendingStatus() {
    assertEquals(NotificationStatus.PENDING, accepted().status());
  }

  @Test
  void acceptRecordsNotificationAcceptedEvent() {
    final Notification notification = accepted();

    final List<DomainEvent> events = notification.pullEvents();

    assertEquals(1, events.size());
    assertInstanceOf(NotificationAccepted.class, events.get(0));
  }

  @Test
  void acceptRejectsNullTenantId() {

    final ExternalId externalId = ExternalId.of("order-42");
    final ChannelType channelType = ChannelType.of("EMAIL");
    final RecipientId recipientId = RecipientId.of("recipient-1");
    final Recipient recipient = Recipient.of("alice@example.com");
    final NotificationDetails details =
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL);
    assertThrows(
        NullPointerException.class,
        () ->
            Notification.accept(
                new NotificationRouting(null, externalId, channelType, recipientId, recipient),
                details));
  }

  @Test
  void acceptRejectsNullRecipientId() {
    assertThrows(
        NullPointerException.class,
        () ->
            Notification.accept(
                new NotificationRouting(
                    null,
                    ExternalId.of("order-42"),
                    ChannelType.of("EMAIL"),
                    RecipientId.of("recipient-1"),
                    Recipient.of("alice@example.com")),
                new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL)));
  }

  @Test
  void acceptRejectsNullContent() {
    assertThrows(
        NullPointerException.class,
        () ->
            Notification.accept(
                new NotificationRouting(
                    TenantId.of("tenant-1"),
                    ExternalId.of("order-42"),
                    ChannelType.of("EMAIL"),
                    null,
                    Recipient.of("alice@example.com")),
                new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL)));
  }

  @Test
  void markQueuedTransitionsToInProcessAndFiresEvent() {
    final Notification notification = accepted();
    notification.pullEvents();

    notification.markQueued();

    assertEquals(NotificationStatus.IN_PROCESS, notification.status());
    assertInstanceOf(NotificationQueued.class, notification.pullEvents().get(0));
  }

  @Test
  void markQueuedFromNonPendingThrows() {
    final Notification notification = accepted();
    notification.markQueued();

    assertThrows(InvalidStatusTransitionException.class, notification::markQueued);
  }

  @Test
  void markDeliveredTransitionsRecordsAttemptAndFiresEvent() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.pullEvents();

    notification.markDelivered(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(NotificationStatus.DELIVERED, notification.status());
    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(AttemptResult.ACCEPTED, notification.deliveryAttempts().get(0).result());
    assertEquals(AttemptOrigin.AUTOMATIC, notification.deliveryAttempts().get(0).origin());
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
    assertInstanceOf(NotificationDelivered.class, notification.pullEvents().get(0));
  }

  @Test
  void markDeliveredFromPendingThrows() {
    final Notification notification = accepted();
    assertThrows(
        InvalidStatusTransitionException.class,
        () -> notification.markDelivered(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo")));
  }

  @Test
  void markRecoverableTransitionsRecordsAttemptAndFiresEvent() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.pullEvents();

    notification.markRecoverable(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, notification.deliveryAttempts().get(0).result());
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
    assertInstanceOf(NotificationRecoverable.class, notification.pullEvents().get(0));
  }

  @Test
  void markRetriesExhaustedTransitionsToFailedButRecordsRecoverableFailureAttempt() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.pullEvents();

    notification.markRetriesExhausted(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(NotificationStatus.FAILED, notification.status());
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, notification.deliveryAttempts().get(0).result());
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
    assertInstanceOf(NotificationFailed.class, notification.pullEvents().get(0));
  }

  @Test
  void markRetriesExhaustedFromPendingThrows() {
    final Notification notification = accepted();
    final ProviderId providerId = ProviderId.of("brevo");
    assertThrows(
        InvalidStatusTransitionException.class,
        () -> notification.markRetriesExhausted(AttemptOrigin.AUTOMATIC, providerId));
  }

  @Test
  void markFailedTransitionsRecordsAttemptAndFiresEvent() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.pullEvents();

    notification.markFailed(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(NotificationStatus.FAILED, notification.status());
    assertEquals(AttemptResult.PERMANENT_FAILURE, notification.deliveryAttempts().get(0).result());
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
    assertInstanceOf(NotificationFailed.class, notification.pullEvents().get(0));
  }

  @Test
  void requeueFromRecoverableGoesToPending() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.markRecoverable(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));
    notification.pullEvents();

    notification.requeue();

    assertEquals(NotificationStatus.PENDING, notification.status());
    assertInstanceOf(NotificationRequeued.class, notification.pullEvents().get(0));
  }

  @Test
  void requeueFromFailedGoesToPending() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.markFailed(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));
    notification.pullEvents();

    notification.requeue();

    assertEquals(NotificationStatus.PENDING, notification.status());
    assertInstanceOf(NotificationRequeued.class, notification.pullEvents().get(0));
  }

  @Test
  void requeueFromPendingThrows() {
    final Notification notification = accepted();
    assertThrows(InvalidStatusTransitionException.class, notification::requeue);
  }

  @Test
  void discardFromPendingGoesToDiscarded() {
    final Notification notification = accepted();
    notification.pullEvents();

    notification.discard();

    assertEquals(NotificationStatus.DISCARDED, notification.status());
    assertInstanceOf(NotificationDiscarded.class, notification.pullEvents().get(0));
  }

  @Test
  void discardFromInProcessThrows() {
    final Notification notification = accepted();
    notification.markQueued();

    assertThrows(InvalidStatusTransitionException.class, notification::discard);
  }

  @Test
  void pullEventsEmptiesAfterBeingRead() {
    final Notification notification = accepted();
    notification.pullEvents();

    assertTrue(notification.pullEvents().isEmpty());
  }

  @Test
  void equalityIsByNotificationIdNotByFieldValues() {
    final NotificationId id = NotificationId.newId();
    final Instant now = Instant.now();

    final Notification first =
        Notification.reconstitute(
            id,
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            NotificationStatus.PENDING,
            new NotificationMetadata(now, 1L),
            List.of());
    final Notification second =
        Notification.reconstitute(
            id,
            new NotificationRouting(
                TenantId.of("tenant-2"),
                ExternalId.of("order-99"),
                ChannelType.of("SMS"),
                RecipientId.of("recipient-2"),
                Recipient.of("bob@example.com")),
            new NotificationDetails(NotificationContent.of("Different body"), Priority.HIGH),
            NotificationStatus.DELIVERED,
            new NotificationMetadata(now.plusSeconds(60), 2L),
            List.of());

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  @Test
  void differentNotificationIdsAreNeverEqual() {
    assertNotEquals(accepted(), accepted());
  }

  @Test
  void isEqualToItself() {
    final Notification notification = accepted();
    assertEquals(notification, notification);
  }

  @Test
  void isNotEqualToNullOrADifferentType() {
    final Notification notification = accepted();
    assertFalse(notification.equals(null));
    assertFalse(notification.equals("not a notification"));
  }

  @Test
  void reconstituteRestoresGivenStateWithoutFiringEvents() {
    final NotificationId id = NotificationId.newId();
    final Instant acceptedAt = Instant.now();
    final List<DeliveryAttempt> attempts =
        List.of(
            DeliveryAttempt.of(
                acceptedAt,
                AttemptResult.RECOVERABLE_FAILURE,
                AttemptOrigin.AUTOMATIC,
                ProviderId.of("brevo")));

    final Notification notification =
        Notification.reconstitute(
            id,
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.HIGH),
            NotificationStatus.RECOVERABLE,
            new NotificationMetadata(acceptedAt, 3L),
            attempts);

    assertEquals(id, notification.notificationId());
    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(3L, notification.version());
    assertTrue(notification.pullEvents().isEmpty());
  }

  @Test
  void gettersReturnGivenValues() {
    final Notification notification = accepted();

    assertEquals(TenantId.of("tenant-1"), notification.tenantId());
    assertEquals(ExternalId.of("order-42"), notification.externalId());
    assertEquals(ChannelType.of("EMAIL"), notification.channelType());
    assertEquals(RecipientId.of("recipient-1"), notification.recipientId());
    assertEquals(Recipient.of("alice@example.com"), notification.recipient());
    assertEquals(NotificationContent.of("Subject", "Body"), notification.content());
    assertEquals(Priority.NORMAL, notification.priority());
    assertTrue(notification.acceptedAt().isBefore(Instant.now().plusSeconds(1)));
  }

  @Test
  void versionIsNullForANewlyAcceptedNotification() {
    assertNull(accepted().version());
  }

  private static Notification reconstituted(
      final NotificationStatus status,
      final NotificationMetadata metadata,
      final List<DeliveryAttempt> attempts) {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL),
        status,
        metadata,
        attempts);
  }

  private static DeliveryAttempt recoverableAttempt(final int cycle) {
    return DeliveryAttempt.of(
        Instant.now(),
        AttemptResult.RECOVERABLE_FAILURE,
        AttemptOrigin.AUTOMATIC,
        ProviderId.of("brevo"),
        cycle);
  }

  @Test
  void aNewlyAcceptedNotificationStartsInCycleOneWithPendingSinceEqualToAcceptedAt() {
    final Notification notification = accepted();

    assertEquals(1, notification.currentCycle());
    assertEquals(notification.acceptedAt(), notification.pendingSince());
    assertNull(notification.dispatchReservedAt());
  }

  @Test
  void aDocumentWithoutTheNewFieldsIsReadWithPendingSinceAcceptedAtAndCycleOne() {
    final Instant acceptedAt = Instant.now().minusSeconds(600);

    final Notification notification =
        reconstituted(
            NotificationStatus.PENDING,
            new NotificationMetadata(acceptedAt, 2L, null, null, 0),
            List.of());

    assertEquals(acceptedAt, notification.pendingSince());
    assertEquals(1, notification.currentCycle());
    assertNull(notification.dispatchReservedAt());
  }

  @Test
  void reconstitutionKeepsThePersistedReservationPendingSinceAndCycle() {
    final Instant acceptedAt = Instant.now().minusSeconds(600);
    final Instant pendingSince = acceptedAt.plusSeconds(100);
    final Instant reservedAt = acceptedAt.plusSeconds(200);

    final Notification notification =
        reconstituted(
            NotificationStatus.IN_PROCESS,
            new NotificationMetadata(acceptedAt, 2L, pendingSince, reservedAt, 3),
            List.of());

    assertEquals(pendingSince, notification.pendingSince());
    assertEquals(reservedAt, notification.dispatchReservedAt());
    assertEquals(3, notification.currentCycle());
  }

  @Test
  void requeueSetsPendingSinceToNowEachTime() {
    final Instant acceptedAt = Instant.now().minusSeconds(600);
    final Notification notification =
        reconstituted(
            NotificationStatus.RECOVERABLE,
            new NotificationMetadata(acceptedAt, 1L),
            List.of(recoverableAttempt(1)));
    final Instant before = Instant.now();

    notification.requeue();

    assertEquals(NotificationStatus.PENDING, notification.status());
    assertFalse(notification.pendingSince().isBefore(before));
  }

  @Test
  void aResultClearsTheDispatchReservation() {
    final Notification notification =
        reconstituted(
            NotificationStatus.IN_PROCESS,
            new NotificationMetadata(
                Instant.now().minusSeconds(60), 1L, null, Instant.now().minusSeconds(5), 1),
            List.of());

    notification.markDelivered(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertNull(notification.dispatchReservedAt());
  }

  @Test
  void announceQueuedRegistersTheQueuedEventWithoutChangingTheState() {
    final Notification notification =
        reconstituted(
            NotificationStatus.IN_PROCESS,
            new NotificationMetadata(Instant.now(), 1L, null, Instant.now(), 1),
            List.of());

    notification.announceQueued();

    assertEquals(NotificationStatus.IN_PROCESS, notification.status());
    assertInstanceOf(NotificationQueued.class, notification.pullEvents().getFirst());
  }

  @Test
  void newAttemptsAreStampedWithTheCurrentCycle() {
    final Notification notification =
        reconstituted(
            NotificationStatus.IN_PROCESS,
            new NotificationMetadata(Instant.now(), 1L, null, Instant.now(), 2),
            List.of(recoverableAttempt(1)));

    notification.markRecoverable(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(2, notification.deliveryAttempts().getLast().cycle());
  }

  @Test
  void theRecoverableCountOnlyCoversTheCurrentCycle() {
    final Notification notification =
        reconstituted(
            NotificationStatus.PENDING,
            new NotificationMetadata(Instant.now(), 1L, null, null, 2),
            List.of(
                recoverableAttempt(1),
                recoverableAttempt(1),
                recoverableAttempt(1),
                recoverableAttempt(2)));

    assertEquals(1, notification.recoverableAttemptsInCurrentCycle());
    assertEquals(4, notification.deliveryAttempts().size());
  }

  @Test
  void theRecoverableCountIgnoresPermanentFailuresOfTheSameCycle() {
    final Notification notification =
        reconstituted(
            NotificationStatus.PENDING,
            new NotificationMetadata(Instant.now(), 1L),
            List.of(
                DeliveryAttempt.of(
                    Instant.now(),
                    AttemptResult.PERMANENT_FAILURE,
                    AttemptOrigin.AUTOMATIC,
                    ProviderId.of("brevo")),
                recoverableAttempt(1)));

    assertEquals(1, notification.recoverableAttemptsInCurrentCycle());
  }

  @Test
  void aManualRetryOfAFailedNotificationStartsANewCycleAndKeepsTheHistory() {
    final Notification notification =
        reconstituted(
            NotificationStatus.FAILED,
            new NotificationMetadata(Instant.now().minusSeconds(900), 4L),
            List.of(
                recoverableAttempt(1),
                recoverableAttempt(1),
                recoverableAttempt(1),
                recoverableAttempt(1),
                recoverableAttempt(1)));
    final Instant before = Instant.now();

    notification.retryManually();

    assertEquals(NotificationStatus.PENDING, notification.status());
    assertEquals(2, notification.currentCycle());
    assertEquals(0, notification.recoverableAttemptsInCurrentCycle());
    assertEquals(5, notification.deliveryAttempts().size());
    assertFalse(notification.pendingSince().isBefore(before));
    assertInstanceOf(NotificationRequeued.class, notification.pullEvents().getFirst());
  }

  @Test
  void afterAManualRetryTheFirstRecoverableFailureCountsAsOneInTheNewCycle() {
    final Notification notification =
        reconstituted(
            NotificationStatus.FAILED,
            new NotificationMetadata(Instant.now().minusSeconds(900), 4L),
            List.of(recoverableAttempt(1), recoverableAttempt(1), recoverableAttempt(1)));
    notification.retryManually();
    notification.markQueued();
    notification.markRecoverable(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(1, notification.recoverableAttemptsInCurrentCycle());
    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
  }

  @Test
  void aManualRetryIsRejectedFromAStateOtherThanFailed() {
    final Notification delivered =
        reconstituted(
            NotificationStatus.DELIVERED, new NotificationMetadata(Instant.now(), 1L), List.of());

    assertThrows(InvalidStatusTransitionException.class, delivered::retryManually);
    assertEquals(1, delivered.currentCycle());
  }
}
