package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

  @AfterEach
  void clear() {
    MDC.clear();
  }

  private static Notification notification() {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-a"),
            ExternalId.of("order-1"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL),
        CorrelationId.of("corr-1"));
  }

  @Test
  void opensTheThreeIdentifiersAndRestoresThePreviousStateOnClose() {
    MDC.put(LogFields.CORRELATION_ID, "outer");

    try (LogContext ignored = LogContext.open(CorrelationId.of("inner"), "tenant-a", "n-1")) {
      assertEquals("inner", MDC.get(LogFields.CORRELATION_ID));
      assertEquals("tenant-a", MDC.get(LogFields.TENANT_ID));
      assertEquals("n-1", MDC.get(LogFields.NOTIFICATION_ID));
    }

    assertEquals("outer", MDC.get(LogFields.CORRELATION_ID));
    assertNull(MDC.get(LogFields.TENANT_ID));
    assertNull(MDC.get(LogFields.NOTIFICATION_ID));
  }

  @Test
  void nullValuesAreSkippedAndLeaveExistingEntriesUntouched() {
    MDC.put(LogFields.TENANT_ID, "kept");

    try (LogContext ignored = LogContext.open(null, null, "n-2")) {
      assertEquals("kept", MDC.get(LogFields.TENANT_ID));
      assertNull(MDC.get(LogFields.CORRELATION_ID));
      assertEquals("n-2", MDC.get(LogFields.NOTIFICATION_ID));
    }

    assertEquals("kept", MDC.get(LogFields.TENANT_ID));
  }

  @Test
  void ofNotificationUsesThePersistedIdentifiers() {
    final Notification notification = notification();

    try (LogContext ignored = LogContext.of(notification)) {
      assertEquals("corr-1", MDC.get(LogFields.CORRELATION_ID));
      assertEquals("tenant-a", MDC.get(LogFields.TENANT_ID));
      assertEquals(notification.notificationId().value(), MDC.get(LogFields.NOTIFICATION_ID));
    }
  }

  @Test
  void ofEventUsesTheIdentifiersCarriedByTheEvent() {
    final Notification notification = notification();

    try (LogContext ignored = LogContext.of(notification.pullEvents().getFirst())) {
      assertEquals("corr-1", MDC.get(LogFields.CORRELATION_ID));
      assertEquals("tenant-a", MDC.get(LogFields.TENANT_ID));
    }
  }
}
