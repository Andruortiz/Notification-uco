package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.RecipientIdentity;
import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.DeviceLifecyclePort;
import co.edu.uco.notification.core.port.out.DeviceRegistryPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.AesGcmTokenProtection;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.Preconditions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class DeviceMongoAdapter implements DeviceRegistryPort, DeviceLifecyclePort {

  public static final String RETENTION_INDEX = "lastSeenAt_retention";
  public static final String REMOVED_METER = "devices.removed";

  private static final Logger LOGGER = LoggerFactory.getLogger(DeviceMongoAdapter.class);
  private static final Duration TOUCH_INTERVAL = Duration.ofHours(24);
  private static final long UPSERT_RETRIES = 3;
  private static final int FINGERPRINT_LOG_LENGTH = 8;

  private final ReactiveMongoTemplate mongoTemplate;
  private final AesGcmTokenProtection protection;
  private final MeterRegistry meterRegistry;

  public DeviceMongoAdapter(
      final ReactiveMongoTemplate mongoTemplate,
      final AesGcmTokenProtection protection,
      final MeterRegistry meterRegistry) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.protection = Preconditions.requireNonNull(protection, "protection must not be null");
    this.meterRegistry =
        Preconditions.requireNonNull(meterRegistry, "meterRegistry must not be null");
  }

  public static String deviceAad(final String tenantId, final String deviceId) {
    return tenantId + "|" + deviceId;
  }

  @Override
  public Mono<RecipientIdentity> findOrCreateIdentity(
      final TenantId tenantId, final String subject) {
    return Mono.defer(
            () ->
                mongoTemplate.findAndModify(
                    identityKey(tenantId, subject),
                    new Update()
                        .setOnInsert("_id", UUID.randomUUID().toString())
                        .setOnInsert("createdAt", Instant.now()),
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    RecipientIdentityDocument.class))
        .retryWhen(retryOnDuplicate())
        .map(DeviceMongoAdapter::toIdentity);
  }

  @Override
  public Mono<Saved> save(
      final TenantId tenantId,
      final RecipientIdentity identity,
      final DevicePlatform platform,
      final String token,
      final String originComponent) {
    return Mono.defer(
            () -> {
              final String deviceId = UUID.randomUUID().toString();
              final Instant now = Instant.now();
              final String fingerprint = protection.fingerprint(token);
              final Update update =
                  new Update()
                      .set("recipientIdentityId", identity.recipientIdentityId())
                      .set("platform", platform.name())
                      .set("originComponent", originComponent)
                      .set("lastSeenAt", now)
                      .setOnInsert("_id", deviceId)
                      .setOnInsert(
                          "protectedToken",
                          protection.protect(token, deviceAad(tenantId.value(), deviceId)))
                      .setOnInsert("keyVersion", protection.keyVersion())
                      .setOnInsert("fingerprintKeyVersion", protection.fingerprintKeyVersion())
                      .setOnInsert("createdAt", now);
              return mongoTemplate
                  .findAndModify(
                      fingerprintKey(tenantId, fingerprint),
                      update,
                      FindAndModifyOptions.options().upsert(true).returnNew(false),
                      DeviceDocument.class)
                  .map(
                      previous ->
                          new Saved(
                              new Device(
                                  previous.deviceId(),
                                  tenantId,
                                  identity.recipientIdentityId(),
                                  platform,
                                  token,
                                  originComponent,
                                  previous.createdAt(),
                                  now),
                              false))
                  .switchIfEmpty(
                      Mono.fromSupplier(
                          () ->
                              new Saved(
                                  new Device(
                                      deviceId,
                                      tenantId,
                                      identity.recipientIdentityId(),
                                      platform,
                                      token,
                                      originComponent,
                                      now,
                                      now),
                                  true)));
            })
        .retryWhen(retryOnDuplicate());
  }

  @Override
  public Flux<Device> findBySubject(final TenantId tenantId, final String subject) {
    return mongoTemplate
        .findOne(identityKey(tenantId, subject), RecipientIdentityDocument.class)
        .flatMapMany(
            identity ->
                mongoTemplate.find(
                    Query.query(
                            Criteria.where("tenantId")
                                .is(tenantId.value())
                                .and("recipientIdentityId")
                                .is(identity.recipientIdentityId()))
                        .with(Sort.by(Sort.Direction.ASC, "createdAt")),
                    DeviceDocument.class))
        .map(this::toDevice);
  }

  @Override
  public Mono<Boolean> remove(
      final TenantId tenantId, final String deviceId, final String subjectOrNull) {
    final Mono<Query> query =
        subjectOrNull == null
            ? Mono.just(deviceKey(tenantId, deviceId, null))
            : mongoTemplate
                .findOne(identityKey(tenantId, subjectOrNull), RecipientIdentityDocument.class)
                .map(identity -> deviceKey(tenantId, deviceId, identity.recipientIdentityId()));
    return query
        .flatMap(key -> mongoTemplate.findAndRemove(key, DeviceDocument.class))
        .doOnNext(removed -> recordRemoval(removed, "manual"))
        .map(removed -> Boolean.TRUE)
        .defaultIfEmpty(Boolean.FALSE);
  }

  @Override
  public Mono<Void> eraseBySubject(final TenantId tenantId, final String subject) {
    return mongoTemplate
        .findOne(identityKey(tenantId, subject), RecipientIdentityDocument.class)
        .flatMap(
            identity ->
                mongoTemplate
                    .remove(
                        Query.query(
                            Criteria.where("tenantId")
                                .is(tenantId.value())
                                .and("recipientIdentityId")
                                .is(identity.recipientIdentityId())),
                        DeviceDocument.class)
                    .doOnNext(
                        result ->
                            meterRegistry
                                .counter(REMOVED_METER, "reason", "erasure")
                                .increment(result.getDeletedCount()))
                    .then(
                        mongoTemplate.remove(
                            Query.query(Criteria.where("_id").is(identity.recipientIdentityId())),
                            RecipientIdentityDocument.class))
                    .doOnSuccess(
                        ignored ->
                            LOGGER.info(
                                LogFields.fields("tenantId", tenantId.value()),
                                "Recipient devices erased")))
        .then();
  }

  @Override
  public Mono<Void> invalidate(final TenantId tenantId, final String address) {
    return Mono.defer(
            () ->
                mongoTemplate.findAndRemove(
                    fingerprintKey(tenantId, protection.fingerprint(address)),
                    DeviceDocument.class))
        .doOnNext(removed -> recordRemoval(removed, "invalid"))
        .then();
  }

  @Override
  public Mono<Void> touch(final TenantId tenantId, final String address) {
    return Mono.defer(
            () -> {
              final Instant now = Instant.now();
              final Query query =
                  fingerprintKey(tenantId, protection.fingerprint(address))
                      .addCriteria(Criteria.where("lastSeenAt").lt(now.minus(TOUCH_INTERVAL)));
              return mongoTemplate.updateFirst(
                  query, new Update().set("lastSeenAt", now), DeviceDocument.class);
            })
        .then();
  }

  public Mono<Void> ensureRetentionIndex(final Duration retention) {
    Preconditions.requireNonNull(retention, "retention must not be null");
    return mongoTemplate
        .indexOps(DeviceDocument.class)
        .getIndexInfo()
        .filter(info -> RETENTION_INDEX.equals(info.getName()))
        .next()
        .flatMap(
            info ->
                info.getExpireAfter().filter(retention::equals).isPresent()
                    ? Mono.just(Boolean.TRUE)
                    : mongoTemplate
                        .executeCommand(
                            new Document("collMod", "devices")
                                .append(
                                    "index",
                                    new Document("name", RETENTION_INDEX)
                                        .append("expireAfterSeconds", retention.toSeconds())))
                        .thenReturn(Boolean.TRUE))
        .switchIfEmpty(
            Mono.defer(
                () ->
                    mongoTemplate
                        .indexOps(DeviceDocument.class)
                        .createIndex(
                            new Index()
                                .named(RETENTION_INDEX)
                                .on("lastSeenAt", Sort.Direction.ASC)
                                .expire(retention))
                        .thenReturn(Boolean.TRUE)))
        .then();
  }

  private void recordRemoval(final DeviceDocument removed, final String reason) {
    meterRegistry.counter(REMOVED_METER, "reason", reason).increment();
    final String fingerprint = removed.tokenFingerprint();
    LOGGER.info(
        LogFields.fields(
            "tenantId",
            removed.tenantId(),
            "deviceId",
            removed.deviceId(),
            "fingerprint",
            fingerprint == null
                ? null
                : fingerprint.substring(0, Math.min(FINGERPRINT_LOG_LENGTH, fingerprint.length())),
            "reason",
            reason),
        "Device removed");
  }

  private Device toDevice(final DeviceDocument document) {
    return new Device(
        document.deviceId(),
        TenantId.of(document.tenantId()),
        document.recipientIdentityId(),
        DevicePlatform.of(document.platform()),
        revealToken(document),
        document.originComponent(),
        document.createdAt(),
        document.lastSeenAt());
  }

  private String revealToken(final DeviceDocument document) {
    return protection
        .tryReveal(document.protectedToken(), deviceAad(document.tenantId(), document.deviceId()))
        .orElseGet(
            () -> {
              LOGGER.warn(
                  LogFields.fields(
                      "tenantId",
                      document.tenantId(),
                      "deviceId",
                      document.deviceId(),
                      "keyVersion",
                      document.keyVersion()),
                  "Stored device token could not be decrypted");
              return AesGcmTokenProtection.UNREADABLE;
            });
  }

  private static RecipientIdentity toIdentity(final RecipientIdentityDocument document) {
    return new RecipientIdentity(
        document.recipientIdentityId(),
        TenantId.of(document.tenantId()),
        document.subject(),
        document.createdAt());
  }

  private static Retry retryOnDuplicate() {
    return Retry.max(UPSERT_RETRIES).filter(DuplicateKeyException.class::isInstance);
  }

  private static Query identityKey(final TenantId tenantId, final String subject) {
    Preconditions.requireNonBlank(subject, "subject must not be blank");
    return Query.query(Criteria.where("tenantId").is(tenantId.value()).and("subject").is(subject));
  }

  private static Query fingerprintKey(final TenantId tenantId, final String fingerprint) {
    return Query.query(
        Criteria.where("tenantId").is(tenantId.value()).and("tokenFingerprint").is(fingerprint));
  }

  private static Query deviceKey(
      final TenantId tenantId, final String deviceId, final String identityIdOrNull) {
    final Criteria criteria =
        Criteria.where("tenantId").is(tenantId.value()).and("_id").is(deviceId);
    if (identityIdOrNull != null) {
      criteria.and("recipientIdentityId").is(identityIdOrNull);
    }
    return Query.query(criteria);
  }
}
