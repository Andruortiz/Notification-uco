package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    notification.markDelivered(AttemptOrigin.AUTOMATIC);

    assertEquals(NotificationStatus.DELIVERED, notification.status());
    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(AttemptResult.ACCEPTED, notification.deliveryAttempts().get(0).result());
    assertEquals(AttemptOrigin.AUTOMATIC, notification.deliveryAttempts().get(0).origin());
    assertInstanceOf(NotificationDelivered.class, notification.pullEvents().get(0));
  }

  @Test
  void markDeliveredFromPendingThrows() {
    final Notification notification = accepted();
    assertThrows(
        InvalidStatusTransitionException.class,
        () -> notification.markDelivered(AttemptOrigin.AUTOMATIC));
  }

  @Test
  void markRecoverableTransitionsAndRecordsAttemptWithoutEvent() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.pullEvents();

    notification.markRecoverable(AttemptOrigin.AUTOMATIC);

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, notification.deliveryAttempts().get(0).result());
    assertTrue(notification.pullEvents().isEmpty());
  }

  @Test
  void markFailedTransitionsRecordsAttemptAndFiresEvent() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.pullEvents();

    notification.markFailed(AttemptOrigin.AUTOMATIC);

    assertEquals(NotificationStatus.FAILED, notification.status());
    assertEquals(AttemptResult.PERMANENT_FAILURE, notification.deliveryAttempts().get(0).result());
    assertInstanceOf(NotificationFailed.class, notification.pullEvents().get(0));
  }

  @Test
  void requeueFromRecoverableGoesToPending() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.markRecoverable(AttemptOrigin.AUTOMATIC);

    notification.requeue();

    assertEquals(NotificationStatus.PENDING, notification.status());
  }

  @Test
  void requeueFromFailedGoesToPending() {
    final Notification notification = accepted();
    notification.markQueued();
    notification.markFailed(AttemptOrigin.AUTOMATIC);
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
  void reconstituteRestoresGivenStateWithoutFiringEvents() {
    final NotificationId id = NotificationId.newId();
    final Instant acceptedAt = Instant.now();
    final List<DeliveryAttempt> attempts =
        List.of(
            DeliveryAttempt.of(
                acceptedAt, AttemptResult.RECOVERABLE_FAILURE, AttemptOrigin.AUTOMATIC));

    final Notification notification =
        Notification.reconstitute(
            id,
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            Recipient.of("alice@example.com"),
            NotificationContent.of("Body"),
            Priority.HIGH,
            NotificationStatus.RECOVERABLE,
            acceptedAt,
            attempts);

    assertEquals(id, notification.notificationId());
    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(1, notification.deliveryAttempts().size());
    assertTrue(notification.pullEvents().isEmpty());
  }

  @Test
  void gettersReturnGivenValues() {
    final Notification notification = accepted();

    assertEquals(TenantId.of("tenant-1"), notification.tenantId());
    assertEquals(ExternalId.of("order-42"), notification.externalId());
    assertEquals(ChannelType.of("EMAIL"), notification.channelType());
    assertEquals(Recipient.of("alice@example.com"), notification.recipient());
    assertEquals(Priority.NORMAL, notification.priority());
  }
}
