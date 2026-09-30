package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemResult;
import java.time.Instant;

final class NotificationBatchDocumentMapper {

  private NotificationBatchDocumentMapper() {}

  static NotificationBatchDocument toDocument(
      final TenantId tenantId, final BatchAcceptedResult result, final Instant submittedAt) {
    return new NotificationBatchDocument(
        null,
        tenantId.value(),
        result.batchId().value(),
        submittedAt,
        result.results().stream().map(NotificationBatchDocumentMapper::toDocument).toList());
  }

  private static BatchItemResultDocument toDocument(final BatchItemResult item) {
    return new BatchItemResultDocument(
        item.externalId().value(),
        item.outcome().name(),
        item.notificationId() == null ? null : item.notificationId().value(),
        item.rejectionReason());
  }
}
