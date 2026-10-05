package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import co.edu.uco.notification.utils.Preconditions;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@EnableConfigurationProperties(ChannelCatalogProperties.class)
public class ChannelCatalogSeeder implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(ChannelCatalogSeeder.class);

  private final ReactiveMongoTemplate mongoTemplate;
  private final ChannelCatalogProperties properties;

  public ChannelCatalogSeeder(
      final ReactiveMongoTemplate mongoTemplate, final ChannelCatalogProperties properties) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
  }

  Mono<Void> seed() {
    final Map<String, ChannelCatalogProperties.ChannelEntry> channels = properties.channels();
    if (channels == null) {
      return Mono.empty();
    }
    return Flux.fromIterable(channels.entrySet())
        .concatMap(entry -> seedIfMissing(entry.getKey(), entry.getValue()))
        .then();
  }

  private Mono<Void> seedIfMissing(
      final String channelType, final ChannelCatalogProperties.ChannelEntry entry) {
    final ChannelCatalogDocument configured =
        new ChannelCatalogDocument(channelType, entry.providers(), entry.contentSchema());
    return mongoTemplate
        .findById(channelType, ChannelCatalogDocument.class)
        .doOnNext(stored -> logStoredChannel(configured, stored))
        .switchIfEmpty(Mono.defer(() -> insert(configured)))
        .then();
  }

  private Mono<ChannelCatalogDocument> insert(final ChannelCatalogDocument configured) {
    return mongoTemplate
        .insert(configured)
        .doOnNext(
            seeded -> LOGGER.info("Catalog channel seeded channelType={}", seeded.channelType()))
        .onErrorResume(DuplicateKeyException.class, error -> Mono.empty());
  }

  private static void logStoredChannel(
      final ChannelCatalogDocument configured, final ChannelCatalogDocument stored) {
    if (Objects.equals(configured.providers(), stored.providers())
        && Objects.equals(configured.contentSchema(), stored.contentSchema())) {
      return;
    }
    LOGGER.info(
        "Catalog channel already stored, application.yml differs and is not applied channelType={}",
        stored.channelType());
  }

  @Override
  public void run(final ApplicationArguments args) {
    seed()
        .doOnError(
            error ->
                LOGGER.error(
                    "Catalog seeding failed, the stored catalog may be incomplete errorType={}"
                        + " message={}",
                    error.getClass().getSimpleName(),
                    error.getMessage()))
        .onErrorResume(error -> Mono.empty())
        .block();
  }
}
