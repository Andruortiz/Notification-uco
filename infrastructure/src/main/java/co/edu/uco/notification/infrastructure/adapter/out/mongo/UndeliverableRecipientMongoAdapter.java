package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.UndeliverableRecipient;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.repository.UndeliverableRecipientRepository;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class UndeliverableRecipientMongoAdapter implements UndeliverableRecipientRepository {

  private final ReactiveMongoTemplate mongoTemplate;

  public UndeliverableRecipientMongoAdapter(final ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
  }

  @Override
  public Mono<Boolean> markIfAbsent(final UndeliverableRecipient recipient) {
    Preconditions.requireNonNull(recipient, "recipient must not be null");
    final Update update =
        new Update()
            .setOnInsert("reason", recipient.reason())
            .setOnInsert("markedAt", recipient.markedAt());
    return mongoTemplate
        .upsert(
            keyOf(recipient.tenantId(), recipient.channelType(), recipient.normalizedAddress()),
            update,
            UndeliverableRecipientDocument.class)
        .map(result -> result.getUpsertedId() != null)
        .onErrorReturn(DuplicateKeyException.class, false);
  }

  @Override
  public Mono<UndeliverableRecipient> find(
      final TenantId tenantId, final ChannelType channelType, final String normalizedAddress) {
    return mongoTemplate
        .findOne(
            keyOf(tenantId, channelType, normalizedAddress), UndeliverableRecipientDocument.class)
        .map(
            document ->
                new UndeliverableRecipient(
                    TenantId.of(document.tenantId()),
                    ChannelType.of(document.channelType()),
                    document.normalizedAddress(),
                    document.reason(),
                    document.markedAt()));
  }

  @Override
  public Mono<Void> remove(
      final TenantId tenantId, final ChannelType channelType, final String normalizedAddress) {
    return mongoTemplate
        .remove(
            keyOf(tenantId, channelType, normalizedAddress), UndeliverableRecipientDocument.class)
        .then();
  }

  private static Query keyOf(
      final TenantId tenantId, final ChannelType channelType, final String normalizedAddress) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonBlank(normalizedAddress, "normalizedAddress must not be blank");
    return Query.query(
        Criteria.where("tenantId")
            .is(tenantId.value())
            .and("channelType")
            .is(channelType.value())
            .and("normalizedAddress")
            .is(normalizedAddress));
  }
}
