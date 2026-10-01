package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.CorrelationId;
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
            document.version());

    assertNull(NotificationDocumentMapper.toDomain(tampered).correlationId());
  }
}
