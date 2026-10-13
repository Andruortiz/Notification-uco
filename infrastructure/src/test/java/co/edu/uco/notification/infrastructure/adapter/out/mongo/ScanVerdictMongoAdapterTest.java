package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.ScanVerdict;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class ScanVerdictMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private static final TenantId TENANT_A = TenantId.of("tenant-a");
  private static final TenantId TENANT_B = TenantId.of("tenant-b");
  private static final Sha256Digest SHA = Sha256Digest.of("file".getBytes(StandardCharsets.UTF_8));
  private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private ScanVerdictMongoAdapter adapterAt(final Instant now) {
    return new ScanVerdictMongoAdapter(
        mongoTemplate, Duration.ofHours(24), Clock.fixed(now, ZoneOffset.UTC));
  }

  @BeforeEach
  void setUp() {
    mongoTemplate.dropCollection(ScanVerdictDocument.class).block();
    adapterAt(NOW).ensureIndexes().block();
  }

  @Test
  void savesAndFindsAVerdictByTenantAndHash() {
    final ScanVerdictMongoAdapter adapter = adapterAt(NOW);

    adapter.save(TENANT_A, SHA, ScanVerdict.infected("Eicar-Test-Signature", "100")).block();

    assertEquals(
        ScanVerdict.infected("Eicar-Test-Signature", "100"), adapter.find(TENANT_A, SHA).block());
  }

  @Test
  void theSameHashInTwoTenantsIsTwoEntriesThatNeverCross() {
    final ScanVerdictMongoAdapter adapter = adapterAt(NOW);

    adapter.save(TENANT_A, SHA, ScanVerdict.infected("Eicar-Test-Signature", "100")).block();

    assertNull(adapter.find(TENANT_B, SHA).block());
    adapter.save(TENANT_B, SHA, ScanVerdict.clean("100")).block();
    assertEquals(ScanVerdict.clean("100"), adapter.find(TENANT_B, SHA).block());
    assertTrue(adapter.find(TENANT_A, SHA).block().isInfected());
    assertEquals(
        2L,
        mongoTemplate
            .count(
                new org.springframework.data.mongodb.core.query.Query(), ScanVerdictDocument.class)
            .block());
  }

  @Test
  void savingAgainReplacesThePreviousVerdict() {
    final ScanVerdictMongoAdapter adapter = adapterAt(NOW);

    adapter.save(TENANT_A, SHA, ScanVerdict.clean("99")).block();
    adapter.save(TENANT_A, SHA, ScanVerdict.clean("100")).block();

    assertEquals(ScanVerdict.clean("100"), adapter.find(TENANT_A, SHA).block());
  }

  @Test
  void aCleanVerdictExpiresAfterItsTimeToLiveButAnInfectedOneDoesNot() {
    adapterAt(NOW).save(TENANT_A, SHA, ScanVerdict.clean("100")).block();
    final Sha256Digest other = Sha256Digest.of("other".getBytes(StandardCharsets.UTF_8));
    adapterAt(NOW).save(TENANT_A, other, ScanVerdict.infected("Eicar", "100")).block();

    final ScanVerdictMongoAdapter later = adapterAt(NOW.plus(Duration.ofHours(25)));

    assertNull(later.find(TENANT_A, SHA).block());
    assertNotNull(later.find(TENANT_A, other).block());
  }

  @Test
  void createsAUniqueTenantHashIndexAndATtlIndex() {
    final List<Document> indexes =
        mongoTemplate
            .getCollection("attachment_scan_verdicts")
            .flatMapMany(collection -> collection.listIndexes())
            .collectList()
            .block();

    assertTrue(
        indexes.stream()
            .anyMatch(
                index ->
                    Boolean.TRUE.equals(index.getBoolean("unique"))
                        && index.get("key", Document.class).containsKey("sha256")
                        && index.get("key", Document.class).containsKey("tenantId")),
        indexes.toString());
    assertTrue(
        indexes.stream().anyMatch(index -> index.containsKey("expireAfterSeconds")),
        indexes.toString());
  }
}
