package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.configuration.AdoptionMode;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.FixedConfiguration;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.domain.configuration.ValidationResult;
import co.edu.uco.notification.core.port.in.ApplyConfigurationChangeUseCase;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import reactor.core.publisher.Mono;

public final class ApplyConfigurationChangeService implements ApplyConfigurationChangeUseCase {

  private final ConfigurationHolder holder;
  private final ConfigurationValidator validator;
  private final ParameterRegistry registry;
  private final FixedConfiguration fixed;
  private final Clock clock;

  public ApplyConfigurationChangeService(
      final ConfigurationHolder holder,
      final ConfigurationValidator validator,
      final FixedConfiguration fixed,
      final Clock clock) {
    this.holder = Preconditions.requireNonNull(holder, "holder must not be null");
    this.validator = Preconditions.requireNonNull(validator, "validator must not be null");
    this.registry = validator.registry();
    this.fixed = Preconditions.requireNonNull(fixed, "fixed must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<ConfigurationChangeOutcome> apply(final ConfigurationChange change) {
    Preconditions.requireNonNull(change, "change must not be null");
    return Mono.fromSupplier(() -> process(change));
  }

  private ConfigurationChangeOutcome process(final ConfigurationChange change) {
    while (true) {
      final ConfigurationSnapshot current = holder.snapshot();
      if (change.version() <= current.version()) {
        return ConfigurationChangeOutcome.ignoredStale(current.version(), change.version());
      }
      final ValidationResult validation = validator.validate(current, change, fixed);
      if (!validation.isValid()) {
        return ConfigurationChangeOutcome.rejected(
            validation.summary(), change.values().keySet(), current.version());
      }
      final Map<String, Long> values = new HashMap<>(current.values());
      final Set<String> changedKeys = new TreeSet<>();
      final Set<String> restartKeys = new TreeSet<>();
      change
          .values()
          .forEach(
              (key, raw) -> {
                final ParameterDescriptor descriptor = registry.find(key).orElseThrow();
                if (descriptor.adoption() == AdoptionMode.RESTART) {
                  restartKeys.add(key);
                  return;
                }
                final long value = descriptor.asInteger(raw).orElseThrow();
                if (!Long.valueOf(value).equals(values.get(key))) {
                  changedKeys.add(key);
                }
                values.put(key, value);
              });
      final Set<String> pending = new HashSet<>(current.pendingRestart());
      pending.addAll(restartKeys);
      final ConfigurationSnapshot next =
          new ConfigurationSnapshot(
              change.version(), ConfigurationSource.PARAMETERS, values, clock.instant(), pending);
      if (holder.compareAndSet(current, next)) {
        return outcomeOf(change, changedKeys, restartKeys, current.version());
      }
    }
  }

  private static ConfigurationChangeOutcome outcomeOf(
      final ConfigurationChange change,
      final Set<String> changedKeys,
      final Set<String> restartKeys,
      final long previousVersion) {
    final boolean onlyRestartKeys = changedKeys.isEmpty() && !restartKeys.isEmpty();
    if (onlyRestartKeys && allKeysRequireRestart(change, restartKeys)) {
      return ConfigurationChangeOutcome.pendingRestart(
          restartKeys, previousVersion, change.version());
    }
    return ConfigurationChangeOutcome.applied(
        changedKeys, restartKeys, previousVersion, change.version());
  }

  private static boolean allKeysRequireRestart(
      final ConfigurationChange change, final Set<String> restartKeys) {
    return restartKeys.containsAll(change.values().keySet());
  }
}
