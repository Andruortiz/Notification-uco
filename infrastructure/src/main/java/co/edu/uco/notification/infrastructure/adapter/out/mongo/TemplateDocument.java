package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "templates")
@CompoundIndex(
    name = "tenant_key_channel_language",
    def = "{'tenantId': 1, 'key': 1, 'channel': 1, 'language': 1}",
    unique = true)
@CompoundIndex(name = "tenant_category", def = "{'tenantId': 1, 'category': 1}")
public record TemplateDocument(
    @Id String id,
    String tenantId,
    String key,
    String category,
    String channel,
    String language,
    String subject,
    String body,
    List<VariableDocument> variables,
    String status,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public TemplateDocument {
    variables = variables == null ? null : List.copyOf(variables);
  }

  public record VariableDocument(String name, boolean required) {}
}
