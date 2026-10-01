package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notifications")
@CompoundIndex(
    name = "tenant_external_unique",
    def = "{'tenantId': 1, 'externalId': 1}",
    unique = true)
@CompoundIndex(name = "tenant_acceptedAt", def = "{'tenantId': 1, 'acceptedAt': -1}")
public record NotificationDocument(
    @Id String id,
    String tenantId,
    String externalId,
    String channelType,
    String recipientId,
    String recipientAddress,
    String contentSubject,
    String contentBody,
    List<AttachmentDocument> attachments,
    Priority priority,
    NotificationStatus status,
    Instant acceptedAt,
    List<DeliveryAttemptDocument> deliveryAttempts,
    String correlationId,
    @Version Long version) {

  public NotificationDocument {
    attachments = attachments == null ? null : List.copyOf(attachments);
    deliveryAttempts = deliveryAttempts == null ? null : List.copyOf(deliveryAttempts);
  }

  @Override
  public List<AttachmentDocument> attachments() {
    return attachments == null ? null : List.copyOf(attachments);
  }

  @Override
  public List<DeliveryAttemptDocument> deliveryAttempts() {
    return deliveryAttempts == null ? null : List.copyOf(deliveryAttempts);
  }
}
