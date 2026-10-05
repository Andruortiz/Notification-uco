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

  public static final String COLLECTION = "configuration_last_known";
  public static final String CURRENT_ID = "current";
}
