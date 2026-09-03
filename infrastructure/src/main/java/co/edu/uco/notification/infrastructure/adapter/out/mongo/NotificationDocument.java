package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.NotificationStatus;
import co.edu.uco.notification.core.domain.Priority;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

// The unique index is a backstop, not the primary idempotency mechanism -- SendNotificationService
// already checks findByTenantAndExternalId before accepting a new notification. This only catches
// the race between that check and the insert (RF-23). Requires
// notification.data.mongodb.auto-index-creation=true (see application.yml) to actually get created.
@Document(collection = "notifications")
@CompoundIndex(
    name = "tenant_external_unique",
    def = "{'tenantId': 1, 'externalId': 1}",
    unique = true)
public record NotificationDocument(
    @Id String id,
    String tenantId,
    String externalId,
    String channelType,
    String recipientId,
    String recipientAddress,
    String contentSubject,
    String contentBody,
    Priority priority,
    NotificationStatus status,
    Instant acceptedAt,
    List<DeliveryAttemptDocument> deliveryAttempts,
    @Version Long version) {

  public NotificationDocument {
    deliveryAttempts = deliveryAttempts == null ? null : List.copyOf(deliveryAttempts);
  }

  // Overrides the generated accessor: List.copyOf in the compact constructor already makes the
  // stored list immutable, but SpotBugs' EI_EXPOSE_REP can't see that -- copying again here (a
  // no-op on an already-immutable list) satisfies the check.
  @Override
  public List<DeliveryAttemptDocument> deliveryAttempts() {
    return deliveryAttempts == null ? null : List.copyOf(deliveryAttempts);
  }
}
