package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class ChannelCatalogE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private ChannelCatalogCache cache;
  private MongoChannelCatalogAdapter adapter;

  @BeforeEach
  void setUp() {
    mongoTemplate.dropCollection(ChannelCatalogDocument.class).block();
    cache = new ChannelCatalogCache();
    adapter = new MongoChannelCatalogAdapter(cache);
  }

  @Test
  void migratesFromPropertiesAndResolvesTheSameRouteAsApplicationYml() {
    final ChannelCatalogProperties properties =
        new ChannelCatalogProperties(
            Map.of("EMAIL", new ChannelCatalogProperties.ChannelEntry(List.of("simulated"), null)));
    final ChannelCatalogSeeder seeder = new ChannelCatalogSeeder(mongoTemplate, properties);
    final ChannelCatalogRefresher refresher = new ChannelCatalogRefresher(mongoTemplate, cache);

    seeder.seed().block();
    refresher.refresh().block();

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("EMAIL"), null))
        .assertNext(route -> assertEquals(List.of(ProviderId.of("simulated")), route.providers()))
        .verifyComplete();
  }

  @Test
  void migrationDoesNothingWhenTheCollectionAlreadyHasData() {
    mongoTemplate.save(new ChannelCatalogDocument("SMS", List.of("otro"), null)).block();
    final ChannelCatalogProperties properties =
        new ChannelCatalogProperties(
            Map.of("EMAIL", new ChannelCatalogProperties.ChannelEntry(List.of("simulated"), null)));
    final ChannelCatalogSeeder seeder = new ChannelCatalogSeeder(mongoTemplate, properties);

    seeder.seed().block();

    StepVerifier.create(mongoTemplate.findById("EMAIL", ChannelCatalogDocument.class))
        .verifyComplete();
  }

  @Test
  void rejectsADuplicateChannelType() {
    mongoTemplate.insert(new ChannelCatalogDocument("EMAIL", List.of("simulated"), null)).block();

    assertThrows(
        DuplicateKeyException.class,
        () ->
            mongoTemplate
                .insert(new ChannelCatalogDocument("EMAIL", List.of("otro"), null))
                .block());
  }
}
