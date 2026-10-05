package co.edu.uco.notification.core.domain.configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProviderTimeoutBelowRequeueIntervalRule implements CrossParameterRule {

  private static final String PROVIDER_PREFIX = "provider.";
  private static final String TIMEOUT_SUFFIX = ".timeout-ms";

  @Override
  public String name() {
    return "ProviderTimeoutBelowRequeueIntervalRule";
  }

  @Override
  public Optional<String> violation(
      final Map<String, Long> values, final FixedConfiguration fixed) {
    final Long interval = values.get(ParameterRegistry.REQUEUE_INTERVAL_MS);
    if (interval == null) {
      return Optional.empty();
    }
    final List<String> offenders =
        values.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(PROVIDER_PREFIX))
            .filter(entry -> entry.getKey().endsWith(TIMEOUT_SUFFIX))
            .filter(entry -> entry.getValue() >= interval)
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .sorted()
            .toList();
    if (offenders.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "provider timeout must be lower than "
            + ParameterRegistry.REQUEUE_INTERVAL_MS
            + "="
            + interval
            + ": "
            + String.join(", ", offenders));
  }
}
