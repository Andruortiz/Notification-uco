package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.UndeliverableRecipient;
import co.edu.uco.notification.core.domain.policy.RecipientFormatPolicy;
import co.edu.uco.notification.core.port.in.RegisterRecipientBounceUseCase;
import co.edu.uco.notification.core.port.out.MessageRecipientRef;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.ProviderMessageLookupPort;
import co.edu.uco.notification.core.repository.UndeliverableRecipientRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import reactor.core.publisher.Mono;

public final class RecipientBounceService implements RegisterRecipientBounceUseCase {

  private static final System.Logger LOGGER =
      System.getLogger(RecipientBounceService.class.getName());

  private final ProviderMessageLookupPort lookupPort;
  private final UndeliverableRecipientRepository repository;
  private final NotificationMetricsPort metricsPort;
  private final Clock clock;

  public RecipientBounceService(
      final ProviderMessageLookupPort lookupPort,
      final UndeliverableRecipientRepository repository,
      final NotificationMetricsPort metricsPort,
      final Clock clock) {
    this.lookupPort = Preconditions.requireNonNull(lookupPort, "lookupPort must not be null");
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
    this.metricsPort = Preconditions.requireNonNull(metricsPort, "metricsPort must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<Boolean> registerHardBounce(final String providerMessageId, final String reason) {
    if (providerMessageId == null || providerMessageId.isBlank()) {
      metricsPort.bounceUnresolved();
      return Mono.just(false);
    }
    return lookupPort
        .findByProviderMessageId(providerMessageId)
        .flatMap(ref -> mark(ref, reason))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  metricsPort.bounceUnresolved();
                  LOGGER.log(System.Logger.Level.INFO, "BOUNCE_EVENT_UNRESOLVED_MESSAGE_DISCARDED");
                  return false;
                }));
  }

  private Mono<Boolean> mark(final MessageRecipientRef ref, final String reason) {
    return repository
        .markIfAbsent(
            new UndeliverableRecipient(
                ref.tenantId(),
                ref.channelType(),
                RecipientFormatPolicy.normalize(ref.channelType(), ref.address()),
                reason,
                clock.instant()))
        .doOnNext(
            created -> {
              if (created) {
                metricsPort.recipientMarkedUndeliverable(ref.tenantId(), ref.channelType());
                LOGGER.log(
                    System.Logger.Level.INFO,
                    "RECIPIENT_MARKED_UNDELIVERABLE tenantId="
                        + ref.tenantId().value()
                        + " notificationId="
                        + ref.notificationId().value()
                        + " channel="
                        + ref.channelType().value());
              }
            });
  }
}
