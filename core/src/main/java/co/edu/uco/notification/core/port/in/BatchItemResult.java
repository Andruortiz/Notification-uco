package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.utils.Preconditions;

public record BatchItemResult(
    ExternalId externalId,
    BatchItemOutcome outcome,
    NotificationId notificationId,
    String rejectionReason) {

  public BatchItemResult {
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(outcome, "outcome must not be null");
    if (outcome == BatchItemOutcome.REJECTED) {
      Preconditions.requireTrue(
          notificationId == null, "notificationId must be null when outcome is REJECTED");
      Preconditions.requireNonBlank(
          rejectionReason, "rejectionReason must not be blank when outcome is REJECTED");
    } else {
      Preconditions.requireNonNull(
          notificationId, "notificationId must not be null unless outcome is REJECTED");
      Preconditions.requireTrue(
          rejectionReason == null, "rejectionReason must be null unless outcome is REJECTED");
    }
  }

  public static BatchItemResult accepted(
      final ExternalId externalId, final NotificationId notificationId) {
    return new BatchItemResult(externalId, BatchItemOutcome.ACCEPTED, notificationId, null);
  }

  public static BatchItemResult duplicate(
      final ExternalId externalId, final NotificationId notificationId) {
    return new BatchItemResult(externalId, BatchItemOutcome.DUPLICATE, notificationId, null);
  }

  public static BatchItemResult rejected(final ExternalId externalId, final String reason) {
    return new BatchItemResult(externalId, BatchItemOutcome.REJECTED, null, reason);
  }
}
