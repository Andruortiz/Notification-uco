package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.catalog")
public record ChannelCatalogProperties(Map<String, ChannelEntry> channels) {

  public ChannelCatalogProperties {
    channels = channels == null ? null : Map.copyOf(channels);
  }

  public record ChannelEntry(List<String> providers, String contentSchema) {

    public ChannelEntry {
      providers = providers == null ? null : List.copyOf(providers);
    }
  }
}
