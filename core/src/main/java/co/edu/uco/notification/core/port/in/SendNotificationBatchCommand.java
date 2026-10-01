package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record SendNotificationBatchCommand(
    TenantId tenantId,
    BatchId batchId,
    List<BatchNotificationItem> items,
    CorrelationId correlationId) {

  public SendNotificationBatchCommand {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(items, "items must not be null");
    Preconditions.requireTrue(!items.isEmpty(), "items must not be empty");
    items = List.copyOf(items);
  }

  public SendNotificationBatchCommand(
      final TenantId tenantId, final BatchId batchId, final List<BatchNotificationItem> items) {
    this(tenantId, batchId, items, null);
  }
}
