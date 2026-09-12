package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@EnableConfigurationProperties(ChannelCatalogProperties.class)
public class ChannelCatalogSeeder implements ApplicationRunner {

  private final ReactiveMongoTemplate mongoTemplate;
  private final ChannelCatalogProperties properties;

  public ChannelCatalogSeeder(
      final ReactiveMongoTemplate mongoTemplate, final ChannelCatalogProperties properties) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
  }

  Mono<Void> seed() {
    return mongoTemplate
        .count(new Query(), ChannelCatalogDocument.class)
        .filter(count -> count == 0)
        .flatMap(count -> Flux.fromIterable(seedDocuments()).flatMap(mongoTemplate::save).then())
        .then();
  }

  private List<ChannelCatalogDocument> seedDocuments() {
    final Map<String, ChannelCatalogProperties.ChannelEntry> channels = properties.channels();
    if (channels == null) {
      return List.of();
    }
    return channels.entrySet().stream()
        .map(
            entry ->
                new ChannelCatalogDocument(
                    entry.getKey(), entry.getValue().providers(), entry.getValue().contentSchema()))
        .toList();
  }

  @Override
  public void run(final ApplicationArguments args) {
    seed().subscribe();
  }
}
