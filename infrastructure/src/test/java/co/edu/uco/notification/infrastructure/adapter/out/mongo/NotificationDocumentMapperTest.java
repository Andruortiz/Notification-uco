package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationDocumentMapperTest {

  private static Notification notification(final CorrelationId correlationId) {
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
  void roundTripPreservesANonNullCorrelationId() {
    final Notification original = notification(CorrelationId.of("corr-rt-1"));

    final NotificationDocument document = NotificationDocumentMapper.toDocument(original);
    final Notification restored = NotificationDocumentMapper.toDomain(document);

    assertEquals("corr-rt-1", document.correlationId());
    assertEquals(original.correlationId(), restored.correlationId());
  }

  @Test
  void roundTripKeepsANullCorrelationIdNull() {
    final NotificationDocument document = NotificationDocumentMapper.toDocument(notification(null));

    assertNull(document.correlationId());
    assertNull(NotificationDocumentMapper.toDomain(document).correlationId());
  }

  @Test
  void aDocumentWithAnInvalidStoredCorrelationIdIsReadAsNull() {
    final NotificationDocument document =
        NotificationDocumentMapper.toDocument(notification(CorrelationId.of("ok-1")));
    final NotificationDocument tampered =
        new NotificationDocument(
            document.id(),
            document.tenantId(),
            document.externalId(),
            document.channelType(),
            document.recipientId(),
            document.recipientAddress(),
            document.contentSubject(),
            document.contentBody(),
            document.attachments(),
            document.priority(),
            document.status(),
            document.acceptedAt(),
            document.deliveryAttempts(),
            "bad value\n",
            document.pendingSince(),
            document.dispatchReservedAt(),
            document.currentCycle(),
            document.version());

    assertNull(NotificationDocumentMapper.toDomain(tampered).correlationId());
  }

  @Test
  void roundTripPreservesPendingSinceReservationAndCycleWithTheAttemptCycles() {
    final Instant acceptedAt = Instant.parse("2026-10-05T10:00:00Z");
    final Instant pendingSince = acceptedAt.plusSeconds(30);
    final Instant reservedAt = acceptedAt.plusSeconds(60);
    final Notification original =
        Notification.reconstitute(
            NotificationId.newId(),
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL),
            NotificationStatus.IN_PROCESS,
            new NotificationMetadata(acceptedAt, 4L, pendingSince, reservedAt, 2),
            List.of(
                DeliveryAttempt.of(
                    acceptedAt,
                    AttemptResult.RECOVERABLE_FAILURE,
                    AttemptOrigin.AUTOMATIC,
                    ProviderId.of("brevo"),
                    1),
                DeliveryAttempt.of(
                    acceptedAt.plusSeconds(10),
                    AttemptResult.RECOVERABLE_FAILURE,
                    AttemptOrigin.MANUAL,
                    ProviderId.of("brevo"),
                    2)));

    final NotificationDocument document = NotificationDocumentMapper.toDocument(original);
    final Notification restored = NotificationDocumentMapper.toDomain(document);

    assertEquals(pendingSince, document.pendingSince());
    assertEquals(reservedAt, document.dispatchReservedAt());
    assertEquals(2, document.currentCycle());
    assertEquals(List.of(1, 2), document.deliveryAttempts().stream().map(a -> a.cycle()).toList());
    assertEquals(pendingSince, restored.pendingSince());
    assertEquals(reservedAt, restored.dispatchReservedAt());
    assertEquals(2, restored.currentCycle());
    assertEquals(List.of(1, 2), restored.deliveryAttempts().stream().map(a -> a.cycle()).toList());
  }

  @Test
  void aDocumentWithoutTheNewFieldsIsReadWithPendingSinceAcceptedAtAndCycleOne() {
    final Instant acceptedAt = Instant.parse("2026-10-05T10:00:00Z");
    final NotificationDocument legacy =
        new NotificationDocument(
            NotificationId.newId().value(),
            "tenant-1",
            "order-legacy",
            "EMAIL",
            "recipient-1",
            "alice@example.com",
            "Subject",
            "Body",
            null,
            Priority.NORMAL,
            NotificationStatus.RECOVERABLE,
            acceptedAt,
            List.of(
                new DeliveryAttemptDocument(
                    acceptedAt,
                    AttemptResult.RECOVERABLE_FAILURE,
                    AttemptOrigin.AUTOMATIC,
                    "brevo",
                    null)),
            null,
            null,
            null,
            null,
            2L);

    final Notification restored = NotificationDocumentMapper.toDomain(legacy);

    assertEquals(acceptedAt, restored.pendingSince());
    assertEquals(1, restored.currentCycle());
    assertNull(restored.dispatchReservedAt());
    assertEquals(1, restored.deliveryAttempts().getFirst().cycle());
    assertEquals(1, restored.recoverableAttemptsInCurrentCycle());
  }

  @Test
  void aNewlyAcceptedNotificationIsWrittenWithPendingSinceEqualToAcceptedAtAndCycleOne() {
    final NotificationDocument document = NotificationDocumentMapper.toDocument(notification(null));

    assertEquals(document.acceptedAt(), document.pendingSince());
    assertEquals(1, document.currentCycle());
    assertNull(document.dispatchReservedAt());
  }
}
