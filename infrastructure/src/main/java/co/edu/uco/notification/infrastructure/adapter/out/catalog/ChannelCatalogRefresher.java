package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ChannelCatalogRefresher {

  private final ReactiveMongoTemplate mongoTemplate;
  private final ChannelCatalogCache cache;

  public ChannelCatalogRefresher(
      final ReactiveMongoTemplate mongoTemplate, final ChannelCatalogCache cache) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.cache = Preconditions.requireNonNull(cache, "cache must not be null");
  }

  Mono<Void> refresh() {
    return mongoTemplate
        .findAll(ChannelCatalogDocument.class)
        .flatMap(
            document ->
                Mono.justOrEmpty(toRoute(document))
                    .map(route -> Map.entry(document.channelType().toUpperCase(), route)))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .doOnNext(cache::replace)
        .onErrorResume(error -> Mono.empty())
        .then();
  }

  private static Optional<ChannelRoute> toRoute(final ChannelCatalogDocument document) {
    if (document.providers() == null || document.providers().isEmpty()) {
      return Optional.empty();
    }
    final List<ProviderId> providers = document.providers().stream().map(ProviderId::of).toList();
    return Optional.of(
        new ChannelRoute(
            ChannelType.of(document.channelType()), providers, document.contentSchema()));
  }

  @Scheduled(fixedDelayString = "${notification.catalog.refresh-interval-ms:30000}")
  public void refreshCatalog() {
    refresh().subscribe();
  }
}
