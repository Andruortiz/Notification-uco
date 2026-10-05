package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemOutcome;
import co.edu.uco.notification.core.port.in.BatchItemResult;
import java.time.Instant;
import java.util.List;

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

  static BatchAcceptedResult toResult(final NotificationBatchDocument document) {
    final List<BatchItemResultDocument> stored =
        document.results() == null ? List.of() : document.results();
    return new BatchAcceptedResult(
        BatchId.of(document.batchId()),
        stored.stream().map(NotificationBatchDocumentMapper::toResult).toList(),
        true);
  }

  private static BatchItemResultDocument toDocument(final BatchItemResult item) {
    return new BatchItemResultDocument(
        item.externalId().value(),
        item.outcome().name(),
        item.notificationId() == null ? null : item.notificationId().value(),
        item.rejectionReason());
  }

  private static BatchItemResult toResult(final BatchItemResultDocument item) {
    return new BatchItemResult(
        ExternalId.of(item.externalId()),
        BatchItemOutcome.valueOf(item.outcome()),
        item.notificationId() == null ? null : NotificationId.of(item.notificationId()),
        item.rejectionReason());
  }
}
