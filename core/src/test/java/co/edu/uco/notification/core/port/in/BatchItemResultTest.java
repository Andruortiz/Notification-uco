package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.NotificationId;
import org.junit.jupiter.api.Test;

class BatchItemResultTest {

  private static final ExternalId EXTERNAL_ID = ExternalId.of("order-42");

  @Test
  void acceptedFactorySetsOutcomeAndNotificationId() {
    final NotificationId id = NotificationId.newId();

    final BatchItemResult result = BatchItemResult.accepted(EXTERNAL_ID, id);

    assertEquals(EXTERNAL_ID, result.externalId());
    assertEquals(BatchItemOutcome.ACCEPTED, result.outcome());
    assertEquals(id, result.notificationId());
    assertNull(result.rejectionReason());
  }

  @Test
  void duplicateFactorySetsOutcomeAndNotificationId() {
    final NotificationId id = NotificationId.newId();

    final BatchItemResult result = BatchItemResult.duplicate(EXTERNAL_ID, id);

    assertEquals(BatchItemOutcome.DUPLICATE, result.outcome());
    assertEquals(id, result.notificationId());
    assertNull(result.rejectionReason());
  }

  @Test
  void rejectedFactorySetsReasonAndNoNotificationId() {
    final BatchItemResult result = BatchItemResult.rejected(EXTERNAL_ID, "Channel not available");

    assertEquals(BatchItemOutcome.REJECTED, result.outcome());
    assertNull(result.notificationId());
    assertEquals("Channel not available", result.rejectionReason());
  }

  @Test
  void rejectsNullExternalId() {
    assertThrows(
        NullPointerException.class,
        () -> new BatchItemResult(null, BatchItemOutcome.ACCEPTED, NotificationId.newId(), null));
  }

  @Test
  void rejectsNullOutcome() {
    assertThrows(
        NullPointerException.class,
        () -> new BatchItemResult(EXTERNAL_ID, null, NotificationId.newId(), null));
  }

  @Test
  void rejectsNotificationIdOnRejectedOutcome() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BatchItemResult(
                EXTERNAL_ID, BatchItemOutcome.REJECTED, NotificationId.newId(), "reason"));
  }

  @Test
  void rejectsBlankRejectionReasonOnRejectedOutcome() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BatchItemResult(EXTERNAL_ID, BatchItemOutcome.REJECTED, null, "  "));
  }

  @Test
  void rejectsMissingNotificationIdOnAcceptedOutcome() {
    assertThrows(
        NullPointerException.class,
        () -> new BatchItemResult(EXTERNAL_ID, BatchItemOutcome.ACCEPTED, null, null));
  }

  @Test
  void rejectsRejectionReasonOnAcceptedOutcome() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BatchItemResult(
                EXTERNAL_ID, BatchItemOutcome.ACCEPTED, NotificationId.newId(), "reason"));
  }
}
