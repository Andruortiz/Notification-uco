package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.TenantId;
import org.junit.jupiter.api.Test;

class GetNotificationStatusQueryTest {

  @Test
  void preservesGivenValues() {
    final TenantId tenantId = TenantId.of("tenant-1");
    final NotificationId notificationId = NotificationId.newId();

    final GetNotificationStatusQuery query =
        new GetNotificationStatusQuery(tenantId, notificationId);

    assertEquals(tenantId, query.tenantId());
    assertEquals(notificationId, query.notificationId());
  }

  @Test
  void rejectsNullTenantId() {
    assertThrows(
        NullPointerException.class,
        () -> new GetNotificationStatusQuery(null, NotificationId.newId()));
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(
        NullPointerException.class,
        () -> new GetNotificationStatusQuery(TenantId.of("tenant-1"), null));
  }
}
