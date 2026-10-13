package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ChannelCatalogRefresherTest {

  private final ReactiveMongoTemplate mongoTemplate = Mockito.mock(ReactiveMongoTemplate.class);
  private final ChannelCatalogCache cache = new ChannelCatalogCache();
  private final ChannelCatalogRefresher refresher =
      new ChannelCatalogRefresher(mongoTemplate, cache);

  @Test
  void replacesTheSnapshotWithDocumentsReadFromMongo() {
    when(mongoTemplate.findAll(ChannelCatalogDocument.class))
        .thenReturn(Flux.just(new ChannelCatalogDocument("EMAIL", List.of("brevo"), "{}")));

    StepVerifier.create(refresher.refresh()).verifyComplete();

    final ChannelRoute route = cache.snapshot().get("EMAIL");
    assertEquals(ChannelType.of("EMAIL"), route.channelType());
    assertEquals(List.of(ProviderId.of("brevo")), route.providers());
  }

  @Test
  void skipsDocumentsWithoutProviders() {
    when(mongoTemplate.findAll(ChannelCatalogDocument.class))
        .thenReturn(Flux.just(new ChannelCatalogDocument("SMS", List.of(), null)));

    StepVerifier.create(refresher.refresh()).verifyComplete();

    assertTrue(cache.snapshot().isEmpty());
  }

  @Test
  void doesNotReplaceTheSnapshotWhenMongoFails() {
    cache.replace(
        Map.of(
            "EMAIL",
            new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), null)));
    when(mongoTemplate.findAll(ChannelCatalogDocument.class))
        .thenReturn(Flux.error(new RuntimeException("mongo down")));

    StepVerifier.create(refresher.refresh()).verifyComplete();

    assertEquals(1, cache.snapshot().size());
    assertEquals("brevo", cache.snapshot().get("EMAIL").preferredProvider().value());
  }

  @Test
  void constructorRejectsNullArguments() {
    assertThrows(NullPointerException.class, () -> new ChannelCatalogRefresher(null, cache));
    assertThrows(
        NullPointerException.class, () -> new ChannelCatalogRefresher(mongoTemplate, null));
  }
}
