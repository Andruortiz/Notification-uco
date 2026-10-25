package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.infrastructure.adapter.out.security.AesGcmTokenProtection;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;

public class PushRecipientEncryptionSweep {

  private static final String PROTECTED_PREFIX = "^" + AesGcmTokenProtection.PREFIX_ROOT;

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
    return batch(batchSize)
        .expand(processed -> processed == 0 ? Mono.empty() : batch(batchSize))
        .reduce(0L, Long::sum);
  }

  private Mono<Long> batch(final int batchSize) {
    final Query pending =
        Query.query(
                Criteria.where("channelType")
                    .is("PUSH")
                    .and("recipientAddress")
                    .not()
                    .regex(PROTECTED_PREFIX))
            .limit(batchSize);
    return mongoTemplate
        .find(pending, NotificationDocument.class)
        .concatMap(this::encrypt)
        .reduce(0L, Long::sum);
  }

  private Mono<Long> encrypt(final NotificationDocument document) {
    final String recipientId =
        NotificationDocumentMapper.revealed(
            document, document.recipientId(), "recipientId", protection);
    final Query unprotected =
        Query.query(
            Criteria.where("_id")
                .is(document.id())
                .and("recipientAddress")
                .not()
                .regex(PROTECTED_PREFIX));
    final Update update =
        new Update()
            .set(
                "recipientAddress",
                protection.protect(
                    document.recipientAddress(),
                    NotificationDocumentMapper.aad(
                        document.tenantId(), document.id(), "recipientAddress")))
            .set(
                "recipientId",
                protection.protect(
                    recipientId,
                    NotificationDocumentMapper.aad(
                        document.tenantId(), document.id(), "recipientId")))
            .set("recipientIdFingerprint", protection.fingerprint(recipientId));
    return mongoTemplate
        .updateFirst(unprotected, update, NotificationDocument.class)
        .map(result -> result.getModifiedCount());
  }
}
