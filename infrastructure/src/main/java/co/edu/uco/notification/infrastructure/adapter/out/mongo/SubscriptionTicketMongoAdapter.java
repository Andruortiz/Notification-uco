package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.SubscriptionTicketPort;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SubscriptionTicketMongoAdapter implements SubscriptionTicketPort {

  private static final Duration TTL_GRACE = Duration.ofSeconds(60);

  private final ReactiveMongoTemplate mongoTemplate;
  private final Clock clock;

  public SubscriptionTicketMongoAdapter(
      final ReactiveMongoTemplate mongoTemplate, final Clock clock) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  Mono<Void> ensureIndexes() {
    return mongoTemplate
        .indexOps(SubscriptionTicketDocument.class)
        .ensureIndex(
            new Index()
                .on("expiresAt", Sort.Direction.ASC)
                .expire(TTL_GRACE)
                .named("expires_at_ttl"))
        .then();
  }

  @Override
  public Mono<Void> save(
      final String fingerprint, final AuthenticatedPrincipal principal, final Instant expiresAt) {
    Preconditions.requireNonBlank(fingerprint, "fingerprint must not be blank");
    Preconditions.requireNonNull(principal, "principal must not be null");
    Preconditions.requireNonNull(expiresAt, "expiresAt must not be null");
    return mongoTemplate
        .insert(
            new SubscriptionTicketDocument(
                fingerprint,
                principal.tenantId().value(),
                principal.role().name(),
                principal.subject(),
                expiresAt))
        .then();
  }

  @Override
  public Mono<AuthenticatedPrincipal> consume(final String fingerprint) {
    Preconditions.requireNonBlank(fingerprint, "fingerprint must not be blank");
    final Query query =
        Query.query(Criteria.where("_id").is(fingerprint).and("expiresAt").gt(clock.instant()));
    return mongoTemplate
        .findAndRemove(query, SubscriptionTicketDocument.class)
        .map(
            document ->
                new AuthenticatedPrincipal(
                    document.subject(),
                    TenantId.of(document.tenantId()),
                    Role.of(document.role())));
  }
}
