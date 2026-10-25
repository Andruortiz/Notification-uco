package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.infrastructure.adapter.out.security.AesGcmTokenProtection;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;

public class PushRecipientEncryptionSweep {

  private static final Logger LOGGER = LoggerFactory.getLogger(PushRecipientEncryptionSweep.class);
  private static final String UNPROTECTED_ADDRESS =
      "^(?!" + AesGcmTokenProtection.PREFIX_ROOT + ").+";

  private final ReactiveMongoTemplate mongoTemplate;
  private final AesGcmTokenProtection protection;

  public PushRecipientEncryptionSweep(
      final ReactiveMongoTemplate mongoTemplate, final AesGcmTokenProtection protection) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.protection = Preconditions.requireNonNull(protection, "protection must not be null");
  }

  public Mono<Long> run(final int batchSize) {
    Preconditions.requireTrue(batchSize > 0, "batchSize must be positive");
    return warnAboutMissingAddresses()
        .then(Mono.defer(() -> batch(batchSize)))
        .expand(candidates -> candidates == 0 ? Mono.empty() : batch(batchSize))
        .reduce(0L, Long::sum);
  }

  private Mono<Void> warnAboutMissingAddresses() {
    final Query missing =
        Query.query(Criteria.where("channelType").is("PUSH").and("recipientAddress").is(null));
    return mongoTemplate
        .count(missing, NotificationDocument.class)
        .doOnNext(
            count -> {
              if (count > 0) {
                LOGGER.warn(
                    LogFields.fields("count", count),
                    "Push notifications without recipient address skipped by the encryption sweep");
              }
            })
        .then();
  }

  private Mono<Long> batch(final int batchSize) {
    final Query pending =
        Query.query(
                Criteria.where("channelType")
                    .is("PUSH")
                    .and("recipientAddress")
                    .regex(UNPROTECTED_ADDRESS))
            .limit(batchSize);
    return mongoTemplate
        .find(pending, NotificationDocument.class)
        .concatMap(this::encrypt)
        .reduce(0L, Long::sum);
  }

  private Mono<Long> encrypt(final NotificationDocument document) {
    final Query unprotected =
        Query.query(
            Criteria.where("_id")
                .is(document.id())
                .and("recipientAddress")
                .regex(UNPROTECTED_ADDRESS));
    final Update update =
        new Update()
            .set(
                "recipientAddress",
                protection.protect(
                    document.recipientAddress(),
                    NotificationDocumentMapper.aad(
                        document.tenantId(), document.id(), "recipientAddress")));
    final Optional<String> recipientId = plainRecipientId(document);
    recipientId.ifPresent(
        id ->
            update
                .set(
                    "recipientId",
                    protection.protect(
                        id,
                        NotificationDocumentMapper.aad(
                            document.tenantId(), document.id(), "recipientId")))
                .set("recipientIdFingerprint", protection.fingerprint(id)));
    return mongoTemplate
        .updateFirst(unprotected, update, NotificationDocument.class)
        .thenReturn(1L);
  }

  private Optional<String> plainRecipientId(final NotificationDocument document) {
    final String stored = document.recipientId();
    if (stored == null) {
      return Optional.empty();
    }
    if (!protection.isProtected(stored)) {
      return Optional.of(stored);
    }
    return protection.tryReveal(
        stored, NotificationDocumentMapper.aad(document.tenantId(), document.id(), "recipientId"));
  }
}
