package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.FixedConfiguration;
import co.edu.uco.notification.core.domain.configuration.ValidationResult;
import co.edu.uco.notification.core.port.in.RestoreLastKnownConfigurationUseCase;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import reactor.core.publisher.Mono;

public final class RestoreLastKnownConfigurationService
    implements RestoreLastKnownConfigurationUseCase {

  private static final System.Logger LOGGER =
      System.getLogger(RestoreLastKnownConfigurationService.class.getName());

  private final LastKnownConfigurationPort lastKnownPort;
  private final ConfigurationHolder holder;
  private final ConfigurationValidator validator;
  private final FixedConfiguration fixed;
  private final Clock clock;
  private final Duration loadTimeout;

  public RestoreLastKnownConfigurationService(
      final LastKnownConfigurationPort lastKnownPort,
      final ConfigurationHolder holder,
      final ConfigurationValidator validator,
      final FixedConfiguration fixed,
      final Clock clock,
      final Duration loadTimeout) {
    this.lastKnownPort =
        Preconditions.requireNonNull(lastKnownPort, "lastKnownPort must not be null");
    this.holder = Preconditions.requireNonNull(holder, "holder must not be null");
    this.validator = Preconditions.requireNonNull(validator, "validator must not be null");
    this.fixed = Preconditions.requireNonNull(fixed, "fixed must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
    this.loadTimeout = Preconditions.requireNonNull(loadTimeout, "loadTimeout must not be null");
  }

  @Override
  public Mono<ConfigurationSnapshot> restore() {
    return Mono.defer(lastKnownPort::load)
        .timeout(loadTimeout)
        .flatMap(this::adopt)
        .onErrorResume(
            error -> {
              LOGGER.log(
                  System.Logger.Level.WARNING,
                  "LAST_KNOWN_UNAVAILABLE reason={0}, using current configuration",
                  error.getClass().getSimpleName());
              return Mono.empty();
            })
        .switchIfEmpty(Mono.fromSupplier(holder::snapshot));
  }

  private Mono<ConfigurationSnapshot> adopt(final ConfigurationSnapshot loaded) {
    final ConfigurationSnapshot candidate =
        new ConfigurationSnapshot(
            loaded.version(),
            ConfigurationSource.LAST_KNOWN,
            loaded.values(),
            clock.instant(),
            Set.of());
    final ValidationResult validation = validator.validate(candidate, fixed);
    if (!validation.isValid()) {
      LOGGER.log(
          System.Logger.Level.WARNING,
          "LAST_KNOWN_DISCARDED version={0} reason={1}",
          loaded.version(),
          validation.summary());
      return Mono.empty();
    }
    final ConfigurationSnapshot current = holder.snapshot();
    if (candidate.version() <= current.version()) {
      return Mono.just(current);
    }
    return holder.compareAndSet(current, candidate)
        ? Mono.just(candidate)
        : Mono.just(holder.snapshot());
  }
}
