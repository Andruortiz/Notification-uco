package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.BatchId;
import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.Priority;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import co.edu.uco.notification.core.domain.TenantId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SendNotificationBatchCommandTest {

  private static BatchNotificationItem item() {
    return new BatchNotificationItem(
        ExternalId.of("order-42"),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  @Test
  void preservesGivenValues() {
    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(
            TenantId.of("tenant-1"), BatchId.of("batch-1"), List.of(item()));

    assertEquals(TenantId.of("tenant-1"), command.tenantId());
    assertEquals(BatchId.of("batch-1"), command.batchId());
    assertEquals(List.of(item()), command.items());
  }

  @Test
  void allowsNullBatchId() {
    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(TenantId.of("tenant-1"), null, List.of(item()));

    assertNull(command.batchId());
  }

  @Test
  void rejectsNullTenantId() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationBatchCommand(null, BatchId.of("batch-1"), List.of(item())));
  }

  @Test
  void rejectsNullItems() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SendNotificationBatchCommand(TenantId.of("tenant-1"), BatchId.of("batch-1"), null));
  }

  @Test
  void rejectsEmptyItems() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SendNotificationBatchCommand(
                TenantId.of("tenant-1"), BatchId.of("batch-1"), List.of()));
  }
}
