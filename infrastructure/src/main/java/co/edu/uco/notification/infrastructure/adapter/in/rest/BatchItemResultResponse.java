package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.BatchItemResult;

public record BatchItemResultResponse(
    String externalId, String outcome, String notificationId, String rejectionReason) {

  static BatchItemResultResponse from(final BatchItemResult result) {
    return new BatchItemResultResponse(
        result.externalId().value(),
        result.outcome().name(),
        result.notificationId() == null ? null : result.notificationId().value(),
        result.rejectionReason());
  }
}
