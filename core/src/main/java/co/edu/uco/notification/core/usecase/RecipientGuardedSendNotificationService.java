package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.policy.RecipientFormatPolicy;
import co.edu.uco.notification.core.exception.InvalidRecipientException;
import co.edu.uco.notification.core.exception.RecipientUndeliverableException;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.repository.UndeliverableRecipientRepository;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class RecipientGuardedSendNotificationService implements SendNotificationUseCase {

  private static final String FORMAT = "format";
  private static final String UNDELIVERABLE = "undeliverable";

  private final SendNotificationUseCase delegate;
  private final UndeliverableRecipientRepository repository;
  private final NotificationMetricsPort metricsPort;

  public RecipientGuardedSendNotificationService(
      final SendNotificationUseCase delegate,
      final UndeliverableRecipientRepository repository,
      final NotificationMetricsPort metricsPort) {
    this.delegate = Preconditions.requireNonNull(delegate, "delegate must not be null");
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
    this.metricsPort = Preconditions.requireNonNull(metricsPort, "metricsPort must not be null");
  }

  @Override
  public Mono<SendNotificationResult> send(final SendNotificationCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");
    return Mono.fromCallable(
            () ->
                RecipientFormatPolicy.validate(
                    command.channelType(), command.recipient().address()))
        .doOnError(
            InvalidRecipientException.class,
            error ->
                metricsPort.recipientRejected(command.tenantId(), command.channelType(), FORMAT))
        .flatMap(normalized -> rejectIfUndeliverable(command, normalized))
        .then(Mono.defer(() -> delegate.send(command)));
  }

  private Mono<Void> rejectIfUndeliverable(
      final SendNotificationCommand command, final String normalized) {
    return repository
        .find(command.tenantId(), command.channelType(), normalized)
        .flatMap(
            mark -> {
              metricsPort.recipientRejected(
                  command.tenantId(), command.channelType(), UNDELIVERABLE);
              return Mono.<Void>error(new RecipientUndeliverableException(command.channelType()));
            })
        .then();
  }
}
