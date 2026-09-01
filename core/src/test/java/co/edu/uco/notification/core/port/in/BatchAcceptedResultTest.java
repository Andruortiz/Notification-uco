package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.BatchId;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.NotificationId;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchAcceptedResultTest {

  @Test
  void preservesGivenValues() {
    final BatchItemResult item =
        BatchItemResult.accepted(ExternalId.of("order-42"), NotificationId.newId());

    final BatchAcceptedResult result =
        new BatchAcceptedResult(BatchId.of("batch-1"), List.of(item));

    assertEquals(BatchId.of("batch-1"), result.batchId());
    assertEquals(List.of(item), result.results());
  }

  @Test
  void rejectsNullBatchId() {
    final BatchItemResult item =
        BatchItemResult.accepted(ExternalId.of("order-42"), NotificationId.newId());

    assertThrows(NullPointerException.class, () -> new BatchAcceptedResult(null, List.of(item)));
  }

  @Test
  void rejectsNullResults() {
    assertThrows(
        NullPointerException.class, () -> new BatchAcceptedResult(BatchId.of("batch-1"), null));
  }

  @Test
  void rejectsEmptyResults() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BatchAcceptedResult(BatchId.of("batch-1"), List.of()));
  }
}
