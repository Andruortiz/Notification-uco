package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.policy.RecipientFormatPolicy;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.FindUndeliverableMarkQuery;
import co.edu.uco.notification.core.port.in.LiftUndeliverableMarkUseCase;
import co.edu.uco.notification.core.port.in.UndeliverableMarkView;
import co.edu.uco.notification.core.repository.UndeliverableRecipientRepository;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class UndeliverableMarkService
    implements FindUndeliverableMarkQuery, LiftUndeliverableMarkUseCase {

  private final UndeliverableRecipientRepository repository;

  public UndeliverableMarkService(final UndeliverableRecipientRepository repository) {
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
  }

  @Override
  public Mono<UndeliverableMarkView> find(
      final TenantId tenantId, final ChannelType channelType, final String address) {
    return Mono.fromCallable(() -> RecipientFormatPolicy.validate(channelType, address))
        .flatMap(
            normalized ->
                repository
                    .find(tenantId, channelType, normalized)
                    .map(
                        mark ->
                            new UndeliverableMarkView(
                                true, channelType, normalized, mark.reason(), mark.markedAt()))
                    .defaultIfEmpty(
                        new UndeliverableMarkView(false, channelType, normalized, null, null)));
  }

  @Override
  public Mono<Void> lift(
      final TenantId tenantId, final ChannelType channelType, final String address) {
    return Mono.fromCallable(() -> RecipientFormatPolicy.validate(channelType, address))
        .flatMap(normalized -> repository.remove(tenantId, channelType, normalized));
  }
}
