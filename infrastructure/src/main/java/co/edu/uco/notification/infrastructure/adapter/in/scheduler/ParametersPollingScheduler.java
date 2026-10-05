package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import co.edu.uco.notification.core.exception.ParametersUnavailableException;
import co.edu.uco.notification.core.port.in.SynchronizeConfigurationUseCase;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class ParametersPollingScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(ParametersPollingScheduler.class);

  private final SynchronizeConfigurationUseCase synchronizeConfigurationUseCase;

  public ParametersPollingScheduler(
      final SynchronizeConfigurationUseCase synchronizeConfigurationUseCase) {
    this.synchronizeConfigurationUseCase =
        Preconditions.requireNonNull(
            synchronizeConfigurationUseCase, "synchronizeConfigurationUseCase must not be null");
  }

  @Scheduled(fixedDelayString = "${notification.parameters.poll-interval-ms:30000}")
  public void poll() {
    Mono.defer(synchronizeConfigurationUseCase::synchronize)
        .onErrorResume(
            ParametersUnavailableException.class,
            error -> {
              LOG.debug("parameters source unavailable: {}", error.getMessage());
              return Mono.empty();
            })
        .onErrorResume(
            error -> {
              LOG.warn("parameters synchronization failed", error);
              return Mono.empty();
            })
        .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "param-" + UUID.randomUUID()))
        .subscribe();
  }
}
