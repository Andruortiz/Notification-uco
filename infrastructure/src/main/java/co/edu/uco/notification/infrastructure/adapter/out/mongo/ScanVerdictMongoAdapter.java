package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.ScanVerdict;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ScanVerdictCachePort;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ScanVerdictMongoAdapter implements ScanVerdictCachePort {

  private final ReactiveMongoTemplate mongoTemplate;
  private final Duration cleanVerdictTtl;
  private final Clock clock;

  @Autowired
  public ScanVerdictMongoAdapter(
      final ReactiveMongoTemplate mongoTemplate, final AttachmentProperties properties) {
    this(mongoTemplate, properties.scan().cleanVerdictTtl(), Clock.systemUTC());
  }

  ScanVerdictMongoAdapter(
      final ReactiveMongoTemplate mongoTemplate,
      final Duration cleanVerdictTtl,
      final Clock clock) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.cleanVerdictTtl =
        Preconditions.requireNonNull(cleanVerdictTtl, "cleanVerdictTtl must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  Mono<Void> ensureIndexes() {
    return mongoTemplate
        .indexOps(ScanVerdictDocument.class)
        .ensureIndex(
            new Index()
                .on("tenantId", Sort.Direction.ASC)
                .on("sha256", Sort.Direction.ASC)
                .unique()
                .named("tenant_sha256_unique"))
        .then(
            mongoTemplate
                .indexOps(ScanVerdictDocument.class)
                .ensureIndex(
                    new Index()
                        .on("expiresAt", Sort.Direction.ASC)
                        .expire(Duration.ZERO)
                        .named("expires_at_ttl")))
        .then();
  }

  @Override
  public Mono<ScanVerdict> find(final TenantId tenantId, final Sha256Digest sha256) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(sha256, "sha256 must not be null");
    final Instant now = clock.instant();
    return mongoTemplate
        .findOne(byTenantAndHash(tenantId, sha256), ScanVerdictDocument.class)
        .filter(document -> document.expiresAt() == null || document.expiresAt().isAfter(now))
        .map(
            document ->
                new ScanVerdict(
                    ScanVerdict.Outcome.valueOf(document.outcome()),
                    document.signature(),
                    document.signatureVersion()));
  }

  @Override
  public Mono<Void> save(
      final TenantId tenantId, final Sha256Digest sha256, final ScanVerdict verdict) {
    Preconditions.requireNonNull(verdict, "verdict must not be null");
    final Instant now = clock.instant();
    final ScanVerdictDocument document =
        new ScanVerdictDocument(
            tenantId.value() + ":" + sha256.hex(),
            tenantId.value(),
            sha256.hex(),
            verdict.outcome().name(),
            verdict.signature(),
            verdict.signatureVersion(),
            now,
            verdict.isInfected() ? null : now.plus(cleanVerdictTtl));
    return mongoTemplate
        .findAndReplace(
            byTenantAndHash(tenantId, sha256), document, FindAndReplaceOptions.options().upsert())
        .onErrorResume(DuplicateKeyException.class, error -> Mono.empty())
        .then();
  }

  private static Query byTenantAndHash(final TenantId tenantId, final Sha256Digest sha256) {
    return Query.query(
        Criteria.where("tenantId").is(tenantId.value()).and("sha256").is(sha256.hex()));
  }
}
