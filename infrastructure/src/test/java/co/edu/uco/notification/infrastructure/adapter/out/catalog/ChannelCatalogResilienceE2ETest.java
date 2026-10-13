package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class ChannelCatalogResilienceE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Test
  void keepsTheLastKnownSnapshotWhenMongoBecomesUnreachable() {
    mongoTemplate.dropCollection(ChannelCatalogDocument.class).block();
    mongoTemplate.insert(new ChannelCatalogDocument("EMAIL", List.of("simulated"), null)).block();

    final ChannelCatalogCache cache = new ChannelCatalogCache();
    final ChannelCatalogRefresher refresher = new ChannelCatalogRefresher(mongoTemplate, cache);
    final MongoChannelCatalogAdapter adapter = new MongoChannelCatalogAdapter(cache);

    refresher.refresh().block();

    MONGO.stop();

    refresher.refresh().block();

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("EMAIL"), null))
        .assertNext(route -> assertEquals(List.of(ProviderId.of("simulated")), route.providers()))
        .verifyComplete();
  }
}
