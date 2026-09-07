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
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.event.NotificationQueued;
import co.edu.uco.notification.core.exception.InvalidStatusTransitionException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationTest {

  private static Notification accepted() {
    return Notification.accept(
        TenantId.of("tenant-1"),
        ExternalId.of("order-42"),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Subject", "Body"),
        Priority.NORMAL);
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
    assertThrows(
        NullPointerException.class,
        () ->
            Notification.accept(
                null,
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                Priority.NORMAL));
  }

  @Test
  void acceptRejectsNullRecipientId() {
    assertThrows(
        NullPointerException.class,
        () ->
            Notification.accept(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                null,
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                Priority.NORMAL));
  }

  @Test
  void acceptRejectsNullContent() {
    assertThrows(
        NullPointerException.class,
        () ->
            Notification.accept(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                null,
                Priority.NORMAL));
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
  void markRecoverableTransitionsAndRecordsAttemptWithoutEvent() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.pullEvents();

    notification.markRecoverable(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, notification.deliveryAttempts().get(0).result());
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
    assertTrue(notification.pullEvents().isEmpty());
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
    assertThrows(
        InvalidStatusTransitionException.class,
        () -> notification.markRetriesExhausted(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo")));
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

    notification.requeue();

    assertEquals(NotificationStatus.PENDING, notification.status());
  }

  @Test
  void requeueFromFailedGoesToPending() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.markFailed(AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));
    notification.pullEvents();

    notification.requeue();

    assertEquals(NotificationStatus.PENDING, notification.status());
  }

  @Test
  void requeueFromPendingThrows() {
    final Notification notification = accepted();
    assertThrows(InvalidStatusTransitionException.class, notification::requeue);
  }

  @Test
  void discardFromPendingGoesToDiscarded() {
    final Notification notification = accepted();

    notification.discard();

    assertEquals(NotificationStatus.DISCARDED, notification.status());
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
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com"),
            NotificationContent.of("Body"),
            Priority.NORMAL,
            NotificationStatus.PENDING,
            now,
            List.of(),
            1L);
    final Notification second =
        Notification.reconstitute(
            id,
            TenantId.of("tenant-2"),
            ExternalId.of("order-99"),
            ChannelType.of("SMS"),
            RecipientId.of("recipient-2"),
            Recipient.of("bob@example.com"),
            NotificationContent.of("Different body"),
            Priority.HIGH,
            NotificationStatus.DELIVERED,
            now.plusSeconds(60),
            List.of(),
            2L);

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
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com"),
            NotificationContent.of("Body"),
            Priority.HIGH,
            NotificationStatus.RECOVERABLE,
            acceptedAt,
            attempts,
            3L);

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
}
