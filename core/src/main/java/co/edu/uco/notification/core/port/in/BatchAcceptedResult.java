package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record BatchAcceptedResult(
    BatchId batchId, List<BatchItemResult> results, boolean trackingSaved) {

  public BatchAcceptedResult {
    Preconditions.requireNonNull(batchId, "batchId must not be null");
    Preconditions.requireNonNull(results, "results must not be null");
    Preconditions.requireTrue(!results.isEmpty(), "results must not be empty");
    results = List.copyOf(results);
  }

  public BatchAcceptedResult(final BatchId batchId, final List<BatchItemResult> results) {
    this(batchId, results, true);
  }

  public BatchAcceptedResult withTrackingSaved(final boolean saved) {
    return new BatchAcceptedResult(batchId, results, saved);
  }
}
