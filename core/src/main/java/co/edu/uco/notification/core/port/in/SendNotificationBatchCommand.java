package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.BatchId;
import co.edu.uco.notification.core.domain.TenantId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record SendNotificationBatchCommand(
    TenantId tenantId, BatchId batchId, List<BatchNotificationItem> items) {

  public SendNotificationBatchCommand {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(items, "items must not be null");
    Preconditions.requireTrue(!items.isEmpty(), "items must not be empty");
    items = List.copyOf(items);
  }
}
