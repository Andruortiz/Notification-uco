package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "channel_catalog")
public record ChannelCatalogDocument(
    @Id String channelType, List<String> providers, String contentSchema) {

  public ChannelCatalogDocument {
    providers = providers == null ? null : List.copyOf(providers);
  }

  @Override
  public List<String> providers() {
    return providers == null ? null : List.copyOf(providers);
  }
}
