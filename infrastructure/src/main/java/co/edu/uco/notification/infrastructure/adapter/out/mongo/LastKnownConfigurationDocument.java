package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.annotation.Id;

@org.springframework.data.mongodb.core.mapping.Document(
    collection = LastKnownConfigurationDocument.COLLECTION)
public record LastKnownConfigurationDocument(
    @Id String id,
    long version,
    List<Document> values,
    String schemaHash,
    String source,
    Date adoptedAt) {

  public LastKnownConfigurationDocument {
    values = values == null ? null : List.copyOf(values);
    adoptedAt = adoptedAt == null ? null : new Date(adoptedAt.getTime());
  }

  @Override
  public List<Document> values() {
    return values == null ? null : List.copyOf(values);
  }

  @Override
  public Date adoptedAt() {
    return adoptedAt == null ? null : new Date(adoptedAt.getTime());
  }

  public static final String COLLECTION = "configuration_last_known";
  public static final String CURRENT_ID = "current";
}
