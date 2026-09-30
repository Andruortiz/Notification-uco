package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notification_batches")
@CompoundIndex(name = "tenant_batch_unique", def = "{'tenantId': 1, 'batchId': 1}", unique = true)
public record NotificationBatchDocument(
    @Id String id,
    String tenantId,
    String batchId,
    Instant submittedAt,
    List<BatchItemResultDocument> results) {

  public NotificationBatchDocument {
    results = results == null ? null : List.copyOf(results);
  }

  @Override
  public List<BatchItemResultDocument> results() {
    return results == null ? null : List.copyOf(results);
  }
}
