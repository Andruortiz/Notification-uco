package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import java.util.List;

public record BatchAcceptedResponse(
    String batchId, boolean trackingSaved, List<BatchItemResultResponse> results) {

  public BatchAcceptedResponse {
    results = List.copyOf(results);
  }

  static BatchAcceptedResponse from(final BatchAcceptedResult result) {
    return new BatchAcceptedResponse(
        result.batchId().value(),
        result.trackingSaved(),
        result.results().stream().map(BatchItemResultResponse::from).toList());
  }
}
