package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.ProviderId;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import co.edu.uco.notification.core.domain.TenantId;

final class NotificationDocumentMapper {

  private NotificationDocumentMapper() {}

  static NotificationDocument toDocument(final Notification notification) {
    return new NotificationDocument(
        notification.notificationId().value(),
        notification.tenantId().value(),
        notification.externalId().value(),
        notification.channelType().value(),
        notification.recipientId().value(),
        notification.recipient().address(),
        notification.content().subject(),
        notification.content().body(),
        notification.priority(),
        notification.status(),
        notification.acceptedAt(),
        notification.deliveryAttempts().stream()
            .map(NotificationDocumentMapper::toDocument)
            .toList(),
        notification.version());
  }

  static Notification toDomain(final NotificationDocument document) {
    return Notification.reconstitute(
        NotificationId.of(document.id()),
        TenantId.of(document.tenantId()),
        ExternalId.of(document.externalId()),
        ChannelType.of(document.channelType()),
        RecipientId.of(document.recipientId()),
        Recipient.of(document.recipientAddress()),
        NotificationContent.of(document.contentSubject(), document.contentBody()),
        document.priority(),
        document.status(),
        document.acceptedAt(),
        document.deliveryAttempts().stream().map(NotificationDocumentMapper::toDomain).toList(),
        document.version());
  }

  private static DeliveryAttemptDocument toDocument(final DeliveryAttempt attempt) {
    return new DeliveryAttemptDocument(
        attempt.occurredOn(), attempt.result(), attempt.origin(), attempt.providerId().value());
  }

  private static DeliveryAttempt toDomain(final DeliveryAttemptDocument document) {
    return DeliveryAttempt.of(
        document.occurredOn(),
        document.result(),
        document.origin(),
        ProviderId.of(document.providerId()));
  }
}
