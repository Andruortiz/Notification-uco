package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome.Status;
import co.edu.uco.notification.core.port.in.ApplyConfigurationChangeUseCase;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.ReceivePublishedConfigurationUseCase;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class ReceivePublishedConfigurationService
    implements ReceivePublishedConfigurationUseCase {

  private static final System.Logger LOGGER =
      System.getLogger(ReceivePublishedConfigurationService.class.getName());

  private final ApplyConfigurationChangeUseCase applyUseCase;
  private final LastKnownConfigurationPort lastKnownPort;
  private final ConfigurationView view;

  public ReceivePublishedConfigurationService(
      final ApplyConfigurationChangeUseCase applyUseCase,
      final LastKnownConfigurationPort lastKnownPort,
      final ConfigurationView view) {
    this.applyUseCase = Preconditions.requireNonNull(applyUseCase, "applyUseCase must not be null");
    this.lastKnownPort =
        Preconditions.requireNonNull(lastKnownPort, "lastKnownPort must not be null");
    this.view = Preconditions.requireNonNull(view, "view must not be null");
  }

  @Override
  public Mono<ConfigurationChangeOutcome> receive(final ConfigurationChange change) {
    Preconditions.requireNonNull(change, "change must not be null");
    return Mono.defer(() -> applyUseCase.apply(change)).flatMap(this::persistIfAdopted);
  }

  private Mono<ConfigurationChangeOutcome> persistIfAdopted(
      final ConfigurationChangeOutcome outcome) {
    if (outcome.status() != Status.APPLIED && outcome.status() != Status.PENDING_RESTART) {
      return Mono.just(outcome);
    }
    return Mono.defer(() -> lastKnownPort.saveIfNewer(view.snapshot()))
        .onErrorResume(
            error -> {
              LOGGER.log(
                  System.Logger.Level.WARNING,
                  "LAST_KNOWN_NOT_SAVED version={0} reason={1}",
                  outcome.newVersion(),
                  error.getClass().getSimpleName());
              return Mono.empty();
            })
        .thenReturn(outcome);
  }
}
