package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.utils.Preconditions;

public record BatchItemResult(
    ExternalId externalId,
    BatchItemOutcome outcome,
    NotificationId notificationId,
    String rejectionReason) {

  public static final String INTERNAL_ERROR_REASON = "Internal error";

  public BatchItemResult {
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(outcome, "outcome must not be null");
    if (outcome == BatchItemOutcome.REJECTED || outcome == BatchItemOutcome.FAILED) {
      Preconditions.requireTrue(
          notificationId == null, "notificationId must be null when outcome is REJECTED or FAILED");
      Preconditions.requireNonBlank(
          rejectionReason, "rejectionReason must not be blank when outcome is REJECTED or FAILED");
    } else {
      Preconditions.requireNonNull(
          notificationId, "notificationId must not be null unless outcome is REJECTED or FAILED");
      Preconditions.requireTrue(
          rejectionReason == null,
          "rejectionReason must be null unless outcome is REJECTED or FAILED");
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

  public static BatchItemResult failed(final ExternalId externalId) {
    return new BatchItemResult(externalId, BatchItemOutcome.FAILED, null, INTERNAL_ERROR_REASON);
  }
}
