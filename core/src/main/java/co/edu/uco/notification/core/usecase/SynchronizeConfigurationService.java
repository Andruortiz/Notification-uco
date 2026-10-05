package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome.Status;
import co.edu.uco.notification.core.port.in.ApplyConfigurationChangeUseCase;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.SynchronizeConfigurationUseCase;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Mono;

public final class SynchronizeConfigurationService implements SynchronizeConfigurationUseCase {

  private static final System.Logger LOGGER =
      System.getLogger(SynchronizeConfigurationService.class.getName());

  private final ParametersSourcePort source;
  private final ApplyConfigurationChangeUseCase applyUseCase;
  private final LastKnownConfigurationPort lastKnownPort;
  private final ConfigurationView view;

  public SynchronizeConfigurationService(
      final ParametersSourcePort source,
      final ApplyConfigurationChangeUseCase applyUseCase,
      final LastKnownConfigurationPort lastKnownPort,
      final ConfigurationView view) {
    this.source = Preconditions.requireNonNull(source, "source must not be null");
    this.applyUseCase = Preconditions.requireNonNull(applyUseCase, "applyUseCase must not be null");
    this.lastKnownPort =
        Preconditions.requireNonNull(lastKnownPort, "lastKnownPort must not be null");
    this.view = Preconditions.requireNonNull(view, "view must not be null");
  }

  @Override
  public Mono<ConfigurationChangeOutcome> synchronize() {
    return Mono.defer(source::fetchState)
        .flatMap(
            change -> applyUseCase.apply(change).flatMap(outcome -> persistIfAdopted(outcome)));
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
