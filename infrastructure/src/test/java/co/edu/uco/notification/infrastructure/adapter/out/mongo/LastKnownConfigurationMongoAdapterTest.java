package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class LastKnownConfigurationMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private static final String COLLECTION = LastKnownConfigurationDocument.COLLECTION;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private final ParameterRegistry registry = new ParameterRegistry();

  private LastKnownConfigurationMongoAdapter adapter() {
    return new LastKnownConfigurationMongoAdapter(mongoTemplate, registry);
  }

  private ConfigurationSnapshot snapshot(final long version, final long maxAttempts) {
    final Map<String, Long> values = new HashMap<>();
    for (final ParameterDescriptor descriptor : registry.descriptors()) {
      values.put(descriptor.key(), descriptor.defaultValue());
    }
    values.put(ParameterRegistry.DISPATCH_MAX_ATTEMPTS, maxAttempts);
    return new ConfigurationSnapshot(
        version,
        ConfigurationSource.PARAMETERS,
        values,
        Instant.parse("2026-10-05T10:00:00Z"),
        Set.of());
  }

  @BeforeEach
  void setUp() {
    mongoTemplate.dropCollection(COLLECTION).block();
  }

  @Test
  void savesAndLoadsTheSnapshotWithItsVersionAndValues() {
    assertTrue(adapter().saveIfNewer(snapshot(5, 7)).block());

    final ConfigurationSnapshot loaded = adapter().load().block();

    assertNotNull(loaded);
    assertEquals(5, loaded.version());
    assertEquals(7, loaded.dispatchMaxAttempts());
    assertEquals(snapshot(5, 7).values(), loaded.values());
  }

  @Test
  void anEmptyCollectionLoadsNothing() {
    assertNull(adapter().load().block());
  }

  @Test
  void anOlderOrEqualVersionNeverOverwritesWhileANewerOneDoes() {
    adapter().saveIfNewer(snapshot(5, 7)).block();

    assertFalse(adapter().saveIfNewer(snapshot(4, 2)).block());
    assertFalse(adapter().saveIfNewer(snapshot(5, 9)).block());
    assertEquals(7, adapter().load().block().dispatchMaxAttempts());

    assertTrue(adapter().saveIfNewer(snapshot(6, 8)).block());
    final ConfigurationSnapshot loaded = adapter().load().block();
    assertEquals(6, loaded.version());
    assertEquals(8, loaded.dispatchMaxAttempts());
  }

  @Test
  void concurrentWritesLeaveTheHighestVersion() {
    final List<Long> versions = LongStream.rangeClosed(1, 30).boxed().collect(Collectors.toList());

    Flux.fromIterable(versions)
        .flatMap(
            version ->
                adapter()
                    .saveIfNewer(snapshot(version, 1 + (version % 20)))
                    .subscribeOn(Schedulers.parallel()),
            30)
        .collectList()
        .block();

    final ConfigurationSnapshot loaded = adapter().load().block();
    assertEquals(30, loaded.version());
    assertEquals(1 + (30 % 20), loaded.dispatchMaxAttempts());
  }

  @Test
  void aDocumentWrittenWithADifferentRegistryHashIsIgnored() {
    final ParameterRegistry extended =
        new ParameterRegistry(List.of(ParameterDescriptor.global("test.extra", 1, 1, 5)));
    new LastKnownConfigurationMongoAdapter(mongoTemplate, extended)
        .saveIfNewer(snapshotOf(extended, 3))
        .block();

    assertNull(adapter().load().block());

    adapter().saveIfNewer(snapshot(4, 6)).block();
    assertEquals(4, adapter().load().block().version());
  }

  private static ConfigurationSnapshot snapshotOf(
      final ParameterRegistry source, final long version) {
    final Map<String, Long> values = new HashMap<>();
    for (final ParameterDescriptor descriptor : source.descriptors()) {
      values.put(descriptor.key(), descriptor.defaultValue());
    }
    return new ConfigurationSnapshot(
        version,
        ConfigurationSource.PARAMETERS,
        values,
        Instant.parse("2026-10-05T10:00:00Z"),
        Set.of());
  }

  @Test
  void aDocumentWithCorruptValuesLoadsNothingWhileAnIntactOneLoads() {
    adapter().saveIfNewer(snapshot(5, 7)).block();
    final Document intact = mongoTemplate.findById("current", Document.class, COLLECTION).block();
    assertNotNull(intact);

    final Document corrupt = new Document(intact);
    corrupt.put("values", "not-a-list");
    mongoTemplate.save(corrupt, COLLECTION).block();
    assertNull(adapter().load().block());

    final Document corruptEntry = new Document(intact);
    corruptEntry.put(
        "values", List.of(new Document("key", "dispatch.max-attempts").append("value", "x")));
    mongoTemplate.save(corruptEntry, COLLECTION).block();
    assertNull(adapter().load().block());

    mongoTemplate.save(intact, COLLECTION).block();
    assertEquals(5, adapter().load().block().version());
  }

  @Test
  void theStoredDocumentContainsOnlyRegistryKeysAndNoCredentials() {
    adapter().saveIfNewer(snapshot(5, 7)).block();

    final Document stored = mongoTemplate.findById("current", Document.class, COLLECTION).block();

    assertNotNull(stored);
    assertEquals(
        Set.of("_id", "version", "values", "schemaHash", "source", "adoptedAt"), stored.keySet());
    final Set<String> keys =
        stored.getList("values", Document.class).stream()
            .map(entry -> entry.getString("key"))
            .collect(Collectors.toSet());
    assertEquals(
        registry.descriptors().stream().map(ParameterDescriptor::key).collect(Collectors.toSet()),
        keys);
    final String rendered = stored.toJson().toLowerCase();
    List.of("secret", "password", "token", "credential", "api-key", "base-url")
        .forEach(word -> assertFalse(rendered.contains(word), word));
  }
}
