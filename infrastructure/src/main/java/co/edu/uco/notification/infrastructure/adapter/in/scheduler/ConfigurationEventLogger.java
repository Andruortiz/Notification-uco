package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ConfigurationEventLogger {

  public static final String CONFIG_APPLIED = "CONFIG_APPLIED";
  public static final String CONFIG_REJECTED = "CONFIG_REJECTED";
  public static final String CONFIG_IGNORED = "CONFIG_IGNORED";
  public static final String PARAMETERS_UNAVAILABLE = "PARAMETERS_UNAVAILABLE";
  public static final String PARAMETERS_RECOVERED = "PARAMETERS_RECOVERED";
  public static final String CORRELATION_PREFIX = "param-";

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationEventLogger.class);

  private final AtomicBoolean unavailable = new AtomicBoolean(false);
  private final AtomicReference<String> lastIgnored = new AtomicReference<>();

  public static String newCorrelationId() {
    return CORRELATION_PREFIX + UUID.randomUUID();
  }

  public Mono<ConfigurationChangeOutcome> observe(final Mono<ConfigurationChangeOutcome> cycle) {
    Preconditions.requireNonNull(cycle, "cycle must not be null");
    return Mono.deferContextual(
        context -> {
          final String correlationId =
              context.getOrDefault(CorrelationId.CONTEXT_KEY, newCorrelationId());
          return cycle
              .doOnNext(outcome -> logOutcome(outcome, correlationId))
              .doOnError(error -> logFailure(error, correlationId));
        });
  }

  public void logOutcome(final ConfigurationChangeOutcome outcome, final String correlationId) {
    Preconditions.requireNonNull(outcome, "outcome must not be null");
    try (LogContext ignored = open(correlationId)) {
      if (unavailable.compareAndSet(true, false)) {
        LOGGER.info(
            LogFields.fields("event", PARAMETERS_RECOVERED), "Parameters component recovered");
      }
      switch (outcome.status()) {
        case APPLIED, PENDING_RESTART -> {
          lastIgnored.set(null);
          LOGGER.info(
              LogFields.fields(
                  "event",
                  CONFIG_APPLIED,
                  "previousVersion",
                  outcome.previousVersion(),
                  "newVersion",
                  outcome.newVersion(),
                  "keys",
                  sorted(outcome.keys()),
                  "pendingRestartKeys",
                  sorted(outcome.pendingRestartKeys())),
              "Configuration change applied");
        }
        case REJECTED -> {
          lastIgnored.set(null);
          LOGGER.warn(
              LogFields.fields(
                  "event",
                  CONFIG_REJECTED,
                  "currentVersion",
                  outcome.previousVersion(),
                  "keys",
                  sorted(outcome.keys()),
                  "reason",
                  outcome.reason()),
              "Configuration change rejected");
        }
        case IGNORED_STALE -> {
          final String marker = outcome.previousVersion() + "|" + outcome.reason();
          if (!marker.equals(lastIgnored.getAndSet(marker))) {
            LOGGER.info(
                LogFields.fields(
                    "event",
                    CONFIG_IGNORED,
                    "currentVersion",
                    outcome.previousVersion(),
                    "reason",
                    outcome.reason()),
                "Configuration change ignored");
          }
        }
      }
    }
  }

  public void logFailure(final Throwable error, final String correlationId) {
    if (unavailable.compareAndSet(false, true)) {
      try (LogContext ignored = open(correlationId)) {
        LOGGER.warn(
            LogFields.failure(
                ErrorCode.PARAMETERS_UNAVAILABLE,
                "event",
                PARAMETERS_UNAVAILABLE,
                "errorType",
                error == null ? "unknown" : error.getClass().getSimpleName()),
            "Parameters component unavailable");
      }
    }
  }

  private static LogContext open(final String correlationId) {
    return LogContext.open(CorrelationId.fromOrNull(correlationId), null, null);
  }

  private static List<String> sorted(final java.util.Set<String> keys) {
    return keys.stream().sorted().toList();
  }
}
