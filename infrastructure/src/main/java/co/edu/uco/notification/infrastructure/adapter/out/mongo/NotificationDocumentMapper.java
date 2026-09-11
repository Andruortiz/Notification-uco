package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationMetadata;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.util.List;
import java.util.Objects;

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
        new NotificationRouting(
            TenantId.of(document.tenantId()),
            ExternalId.of(document.externalId()),
            ChannelType.of(document.channelType()),
            RecipientId.of(document.recipientId()),
            Recipient.of(document.recipientAddress())),
        new NotificationDetails(
            NotificationContent.of(document.contentSubject(), document.contentBody()),
            document.priority()),
        document.status(),
        new NotificationMetadata(document.acceptedAt(), document.version()),
        Objects.requireNonNullElse(document.deliveryAttempts(), List.<DeliveryAttemptDocument>of())
            .stream()
            .map(NotificationDocumentMapper::toDomain)
            .toList());
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
