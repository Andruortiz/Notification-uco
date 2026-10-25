package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.RecipientIdentity;
import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.DeviceRegistryPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.AesGcmTokenProtection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class DeviceMongoAdapterTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final TenantId OTHER = TenantId.of("tenant-2");
  private static final String TOKEN = "fcm-token-AAAA-1111";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private AesGcmTokenProtection protection;
  private SimpleMeterRegistry meters;
  private DeviceMongoAdapter adapter;

  private static byte[] key(final int fill) {
    final byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) fill);
    return bytes;
  }

  @BeforeEach
  void setUp() {
    protection = new AesGcmTokenProtection(key(1), 1, key(2), 1);
    meters = new SimpleMeterRegistry();
    adapter = new DeviceMongoAdapter(mongoTemplate, protection, meters);
    mongoTemplate.dropCollection(DeviceDocument.class).block();
    mongoTemplate.dropCollection(RecipientIdentityDocument.class).block();
    mongoTemplate
        .indexOps(DeviceDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(Document.parse("{'tenantId': 1, 'tokenFingerprint': 1}"))
                .unique()
                .named("tenant_fingerprint_unique"))
        .block();
    mongoTemplate
        .indexOps(RecipientIdentityDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(Document.parse("{'tenantId': 1, 'subject': 1}"))
                .unique()
                .partial(
                    org.springframework.data.mongodb.core.index.PartialIndexFilter.of(
                        Document.parse("{'subject': {'$type': 'string'}}")))
                .named("tenant_subject_unique"))
        .block();
  }

  private RecipientIdentity identity(final TenantId tenant, final String subject) {
    return adapter.findOrCreateIdentity(tenant, subject).block();
  }

  private DeviceRegistryPort.Saved register(
      final TenantId tenant, final String subject, final String token) {
    return adapter
        .save(tenant, identity(tenant, subject), DevicePlatform.ANDROID, token, subject)
        .block();
  }

  private long count() {
    return mongoTemplate.count(new Query(), DeviceDocument.class).block();
  }

  @Test
  void anIdentityIsCreatedOnceAndReusedPerTenantAndSubject() {
    final RecipientIdentity first = identity(TENANT, "app-1");
    final RecipientIdentity again = identity(TENANT, "app-1");
    final RecipientIdentity otherTenant = identity(OTHER, "app-1");

    assertEquals(first.recipientIdentityId(), again.recipientIdentityId());
    assertNotEquals(first.recipientIdentityId(), otherTenant.recipientIdentityId());
    assertEquals("app-1", first.subject());
  }

  @Test
  void theTokenIsStoredEncryptedWithAFingerprintAndRevealedOnRead() {
    final DeviceRegistryPort.Saved saved = register(TENANT, "app-1", TOKEN);

    final DeviceDocument stored =
        mongoTemplate.findById(saved.device().deviceId(), DeviceDocument.class).block();
    assertNotNull(stored);
    assertTrue(saved.created());
    assertFalse(stored.protectedToken().contains(TOKEN));
    assertEquals(protection.fingerprint(TOKEN), stored.tokenFingerprint());
    assertEquals(1, stored.keyVersion());
    assertEquals(1, stored.fingerprintKeyVersion());
    StepVerifier.create(adapter.findBySubject(TENANT, "app-1"))
        .assertNext(device -> assertEquals(TOKEN, device.token()))
        .verifyComplete();
  }

  @Test
  void registeringAnExistingTokenKeepsOneDocumentAndMovesItToTheSession() {
    final DeviceRegistryPort.Saved first = register(TENANT, "app-1", TOKEN);
    final DeviceRegistryPort.Saved second = register(TENANT, "app-2", TOKEN);

    assertFalse(second.created());
    assertEquals(first.device().deviceId(), second.device().deviceId());
    assertEquals(1, count());
    StepVerifier.create(adapter.findBySubject(TENANT, "app-1")).verifyComplete();
    StepVerifier.create(adapter.findBySubject(TENANT, "app-2")).expectNextCount(1).verifyComplete();
  }

  @Test
  void theSameTokenInTwoTenantsAreIndependentRecords() {
    register(TENANT, "app-1", TOKEN);
    register(OTHER, "app-1", TOKEN);

    assertEquals(2, count());
    assertTrue(adapter.remove(TENANT, deviceIdOf(TENANT, "app-1"), "app-1").block());
    assertEquals(1, count());
    StepVerifier.create(adapter.findBySubject(OTHER, "app-1")).expectNextCount(1).verifyComplete();
  }

  private String deviceIdOf(final TenantId tenant, final String subject) {
    return adapter.findBySubject(tenant, subject).blockFirst().deviceId();
  }

  @Test
  void twentyConcurrentRegistrationsOfTheSameTokenLeaveExactlyOneDocument() {
    final RecipientIdentity identity = identity(TENANT, "app-1");

    final List<DeviceRegistryPort.Saved> results =
        Flux.range(0, 20)
            .flatMap(i -> adapter.save(TENANT, identity, DevicePlatform.WEB, TOKEN, "app-1"), 20)
            .collectList()
            .block(Duration.ofSeconds(30));

    assertNotNull(results);
    assertEquals(20, results.size());
    assertEquals(1, count());
    assertEquals(1, results.stream().filter(DeviceRegistryPort.Saved::created).count());
  }

  @Test
  void removalIsScopedToTheSubjectAndAnswersFalseForAnythingElse() {
    final String deviceId = register(TENANT, "app-1", TOKEN).device().deviceId();

    assertFalse(adapter.remove(TENANT, deviceId, "app-2").block());
    assertFalse(adapter.remove(OTHER, deviceId, null).block());
    assertFalse(adapter.remove(TENANT, "missing", "app-1").block());
    assertEquals(1, count());
    assertTrue(adapter.remove(TENANT, deviceId, "app-1").block());
    assertEquals(0, count());
    assertEquals(1.0, meters.counter(DeviceMongoAdapter.REMOVED_METER, "reason", "manual").count());
    assertFalse(adapter.remove(TENANT, deviceId, "app-1").block());
  }

  @Test
  void anAdministratorRemovalWithoutSubjectReachesAnyDeviceOfTheTenant() {
    final String deviceId = register(TENANT, "app-1", TOKEN).device().deviceId();

    assertTrue(adapter.remove(TENANT, deviceId, null).block());
  }

  @Test
  void erasingASubjectRemovesItsIdentityAndDevicesOnly() {
    register(TENANT, "app-1", TOKEN);
    register(TENANT, "app-1", TOKEN + "-b");
    register(TENANT, "app-2", TOKEN + "-c");
    register(OTHER, "app-1", TOKEN);

    adapter.eraseBySubject(TENANT, "app-1").block();

    assertEquals(2, count());
    assertEquals(
        0,
        mongoTemplate
            .count(identityQuery(TENANT, "app-1"), RecipientIdentityDocument.class)
            .block());
    assertEquals(
        2.0, meters.counter(DeviceMongoAdapter.REMOVED_METER, "reason", "erasure").count());
    adapter.eraseBySubject(TENANT, "nobody").block();
  }

  private static Query identityQuery(final TenantId tenant, final String subject) {
    return Query.query(Criteria.where("tenantId").is(tenant.value()).and("subject").is(subject));
  }

  @Test
  void invalidatingATokenRemovesOnlyThatTenantsDeviceAndToleratesUnknownTokens() {
    register(TENANT, "app-1", TOKEN);
    register(OTHER, "app-1", TOKEN);

    adapter.invalidate(TENANT, TOKEN).block();
    adapter.invalidate(TENANT, "never-registered").block();

    assertEquals(1, count());
    StepVerifier.create(adapter.findBySubject(OTHER, "app-1")).expectNextCount(1).verifyComplete();
    assertEquals(
        1.0, meters.counter(DeviceMongoAdapter.REMOVED_METER, "reason", "invalid").count());
  }

  @Test
  void touchWritesAtMostOncePerDay() {
    final String deviceId = register(TENANT, "app-1", TOKEN).device().deviceId();
    final Instant stale = Instant.now().minus(Duration.ofDays(3));
    mongoTemplate
        .updateFirst(
            Query.query(Criteria.where("_id").is(deviceId)),
            new Update().set("lastSeenAt", stale),
            DeviceDocument.class)
        .block();

    adapter.touch(TENANT, TOKEN).block();
    final Instant refreshed =
        mongoTemplate.findById(deviceId, DeviceDocument.class).block().lastSeenAt();
    adapter.touch(TENANT, TOKEN).block();
    final Instant unchanged =
        mongoTemplate.findById(deviceId, DeviceDocument.class).block().lastSeenAt();

    assertTrue(refreshed.isAfter(stale.plus(Duration.ofDays(2))));
    assertEquals(refreshed, unchanged);
    adapter.touch(OTHER, TOKEN).block();
    adapter.touch(TENANT, "never-registered").block();
  }

  @Test
  void theRetentionIndexIsCreatedAndAdjustedWhenThePropertyChanges() {
    adapter.ensureRetentionIndex(Duration.ofDays(270)).block();
    assertEquals(Duration.ofDays(270), retention());

    adapter.ensureRetentionIndex(Duration.ofDays(270)).block();
    adapter.ensureRetentionIndex(Duration.ofDays(30)).block();

    assertEquals(Duration.ofDays(30), retention());
  }

  private Duration retention() {
    return mongoTemplate
        .indexOps(DeviceDocument.class)
        .getIndexInfo()
        .filter(info -> DeviceMongoAdapter.RETENTION_INDEX.equals(info.getName()))
        .blockFirst()
        .getExpireAfter()
        .orElseThrow();
  }

  @Test
  void anIdentityWithoutSubjectAndWithUnknownAttributesPersistsAndReads() {
    mongoTemplate
        .getCollection("recipient_identities")
        .flatMap(
            collection ->
                reactor.core.publisher.Mono.from(
                    collection.insertOne(
                        new Document("_id", "identity-future")
                            .append("tenantId", TENANT.value())
                            .append("createdAt", new java.util.Date())
                            .append("emailAddresses", List.of("a@example.com"))
                            .append("preferences", new Document("quietHours", true)))))
        .block();
    mongoTemplate
        .getCollection("recipient_identities")
        .flatMap(
            collection ->
                reactor.core.publisher.Mono.from(
                    collection.insertOne(
                        new Document("_id", "identity-future-2")
                            .append("tenantId", TENANT.value())
                            .append("createdAt", new java.util.Date()))))
        .block();

    final RecipientIdentityDocument read =
        mongoTemplate.findById("identity-future", RecipientIdentityDocument.class).block();

    assertNotNull(read);
    assertNull(read.subject());
    assertEquals(
        2,
        mongoTemplate
            .count(
                Query.query(Criteria.where("tenantId").is(TENANT.value())),
                RecipientIdentityDocument.class)
            .block());
    final Device device =
        adapter
            .save(
                TENANT,
                new RecipientIdentity("identity-future", TENANT, null, Instant.now()),
                DevicePlatform.IOS,
                TOKEN,
                "app-1")
            .block()
            .device();
    assertEquals("identity-future", device.recipientIdentityId());
  }

  @Test
  void aTokenEncryptedWithAnotherKeyDoesNotBreakTheListing() {
    final DeviceMongoAdapter foreign =
        new DeviceMongoAdapter(
            mongoTemplate, new AesGcmTokenProtection(key(9), 1, key(2), 1), meters);
    final RecipientIdentity identity = identity(TENANT, "app-1");
    foreign.save(TENANT, identity, DevicePlatform.WEB, "foreign-token-1", "app-1").block();
    adapter.save(TENANT, identity, DevicePlatform.WEB, TOKEN, "app-1").block();

    final List<Device> devices = adapter.findBySubject(TENANT, "app-1").collectList().block();

    assertEquals(2, devices.size());
    assertTrue(devices.stream().anyMatch(d -> AesGcmTokenProtection.UNREADABLE.equals(d.token())));
    assertTrue(devices.stream().anyMatch(d -> TOKEN.equals(d.token())));
  }
}
