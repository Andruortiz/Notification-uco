package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
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
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
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
        notification.content().attachments().stream()
            .map(attachment -> toDocument(notification.tenantId(), attachment))
            .toList(),
        notification.priority(),
        notification.status(),
        notification.acceptedAt(),
        notification.deliveryAttempts().stream()
            .map(NotificationDocumentMapper::toDocument)
            .toList(),
        notification.version());
  }

  static Notification toDomain(final NotificationDocument document) {
    final TenantId tenantId = TenantId.of(document.tenantId());
    return Notification.reconstitute(
        NotificationId.of(document.id()),
        new NotificationRouting(
            tenantId,
            ExternalId.of(document.externalId()),
            ChannelType.of(document.channelType()),
            RecipientId.of(document.recipientId()),
            Recipient.of(document.recipientAddress())),
        new NotificationDetails(
            NotificationContent.of(
                document.contentSubject(),
                document.contentBody(),
                Objects.requireNonNullElse(document.attachments(), List.<AttachmentDocument>of())
                    .stream()
                    .map(attachment -> toDomain(tenantId, attachment))
                    .toList()),
            document.priority()),
        document.status(),
        new NotificationMetadata(document.acceptedAt(), document.version()),
        Objects.requireNonNullElse(document.deliveryAttempts(), List.<DeliveryAttemptDocument>of())
            .stream()
            .map(NotificationDocumentMapper::toDomain)
            .toList());
  }

  private static AttachmentDocument toDocument(final TenantId owner, final Attachment attachment) {
    requireOwner(owner, attachment.tenantId().value());
    return switch (attachment.source()) {
      case AttachmentSource.EmbeddedContent embedded ->
          new AttachmentDocument(
              attachment.tenantId().value(),
              attachment.fileName(),
              attachment.contentType(),
              attachment.sizeBytes(),
              attachment.sha256().hex(),
              AttachmentDocument.EMBEDDED,
              embedded.bytes(),
              null,
              null);
      case AttachmentSource.StoredObject stored ->
          new AttachmentDocument(
              attachment.tenantId().value(),
              attachment.fileName(),
              attachment.contentType(),
              attachment.sizeBytes(),
              attachment.sha256().hex(),
              AttachmentDocument.OBJECT,
              null,
              stored.uploadId().value(),
              stored.objectKey());
    };
  }

  private static Attachment toDomain(final TenantId owner, final AttachmentDocument document) {
    requireOwner(owner, document.tenantId());
    return new Attachment(
        owner,
        document.fileName(),
        document.contentType(),
        document.sizeBytes(),
        Sha256Digest.fromHex(document.sha256()),
        AttachmentDocument.EMBEDDED.equals(document.storage())
            ? new AttachmentSource.EmbeddedContent(document.content())
            : new AttachmentSource.StoredObject(
                UploadId.of(document.uploadId()), document.objectKey()));
  }

  private static void requireOwner(final TenantId owner, final String attachmentTenant) {
    if (!owner.value().equals(attachmentTenant)) {
      throw new IllegalStateException("an attachment does not belong to its notification's tenant");
    }
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
