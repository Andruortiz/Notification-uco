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
import co.edu.uco.notification.infrastructure.adapter.out.security.AesGcmTokenProtection;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NotificationDocumentMapper {

  private NotificationDocumentMapper() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDocumentMapper.class);
  private static final String PUSH = "PUSH";
  private static final String RECIPIENT_ID_FIELD = "recipientId";
  private static final String RECIPIENT_ADDRESS_FIELD = "recipientAddress";

  static String aad(final String tenantId, final String notificationId, final String field) {
    return tenantId + "|" + notificationId + "|" + field;
  }

  static NotificationDocument toDocument(final Notification notification) {
    return toDocument(notification, null);
  }

  static NotificationDocument toDocument(
      final Notification notification, final AesGcmTokenProtection protection) {
    final String tenant = notification.tenantId().value();
    final String id = notification.notificationId().value();
    final boolean protectRecipient =
        protection != null && PUSH.equals(notification.channelType().value());
    final String recipientId = notification.recipientId().value();
    final String address = notification.recipient().address();
    return new NotificationDocument(
        id,
        tenant,
        notification.externalId().value(),
        notification.channelType().value(),
        protectRecipient
            ? protection.protect(recipientId, aad(tenant, id, RECIPIENT_ID_FIELD))
            : recipientId,
        protectRecipient
            ? protection.protect(address, aad(tenant, id, RECIPIENT_ADDRESS_FIELD))
            : address,
        protectRecipient ? protection.fingerprint(recipientId) : null,
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
        notification.correlationId() == null ? null : notification.correlationId().value(),
        notification.pendingSince(),
        notification.dispatchReservedAt(),
        notification.currentCycle(),
        notification.version());
  }

  static String revealed(
      final NotificationDocument document,
      final String value,
      final String field,
      final AesGcmTokenProtection protection) {
    if (protection == null) {
      return value;
    }
    return protection
        .tryReveal(value, aad(document.tenantId(), document.id(), field))
        .orElseGet(
            () -> {
              LOGGER.warn(
                  LogFields.fields(
                      "tenantId",
                      document.tenantId(),
                      "notificationId",
                      document.id(),
                      "keyVersion",
                      AesGcmTokenProtection.versionOf(value)),
                  "Stored recipient could not be decrypted");
              return AesGcmTokenProtection.UNREADABLE;
            });
  }

  static Notification toDomain(final NotificationDocument document) {
    return toDomain(document, null);
  }

  static Notification toDomain(
      final NotificationDocument document, final AesGcmTokenProtection protection) {
    final TenantId tenantId = TenantId.of(document.tenantId());
    return Notification.reconstitute(
        NotificationId.of(document.id()),
        new NotificationRouting(
            tenantId,
            ExternalId.of(document.externalId()),
            ChannelType.of(document.channelType()),
            RecipientId.of(
                revealed(document, document.recipientId(), RECIPIENT_ID_FIELD, protection)),
            Recipient.of(
                revealed(
                    document, document.recipientAddress(), RECIPIENT_ADDRESS_FIELD, protection))),
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
        new NotificationMetadata(
            document.acceptedAt(),
            document.version(),
            document.pendingSince(),
            document.dispatchReservedAt(),
            document.currentCycle() == null ? 1 : document.currentCycle()),
        Objects.requireNonNullElse(document.deliveryAttempts(), List.<DeliveryAttemptDocument>of())
            .stream()
            .map(NotificationDocumentMapper::toDomain)
            .toList(),
        CorrelationId.fromOrNull(document.correlationId()));
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
        attempt.occurredOn(),
        attempt.result(),
        attempt.origin(),
        attempt.providerId().value(),
        attempt.cycle(),
        attempt.failedOver(),
        attempt.latencyMillis(),
        attempt.failureCause(),
        attempt.correlationId(),
        attempt.messageId());
  }

  private static DeliveryAttempt toDomain(final DeliveryAttemptDocument document) {
    return new DeliveryAttempt(
        document.occurredOn(),
        document.result(),
        document.origin(),
        ProviderId.of(document.providerId()),
        document.cycle() == null ? 1 : document.cycle(),
        Boolean.TRUE.equals(document.failedOver()),
        document.latencyMillis(),
        document.failureCause(),
        document.correlationId(),
        document.messageId());
  }
}
