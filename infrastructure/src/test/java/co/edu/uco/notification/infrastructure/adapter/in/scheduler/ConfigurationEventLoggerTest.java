package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.exception.ParametersUnavailableException;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.utils.CorrelationId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class ConfigurationEventLoggerTest {

  private static final String CORRELATION = "param-test-cycle";
  private static final String CREDENTIAL = "hu2-073-" + "credencial-" + "reconocible";

  private final ConfigurationEventLogger eventLogger = new ConfigurationEventLogger();
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(ConfigurationEventLogger.class)).addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(ConfigurationEventLogger.class)).detachAppender(logs);
  }

  private List<String> lines() {
    return logs.list.stream().map(LogLines::render).toList();
  }

  private long count(final String event) {
    return lines().stream().filter(line -> line.contains("event=" + event)).count();
  }

  @Test
  void appliedChangeLogsPreviousAndNewVersionAndKeysWithCorrelation() {
    eventLogger.logOutcome(
        ConfigurationChangeOutcome.applied(
            Set.of("requeue.interval-ms", "dispatch.max-attempts"), Set.of(), 1, 2),
        CORRELATION);

    assertEquals(1, count("CONFIG_APPLIED"));
    final String line = lines().get(0);
    assertTrue(line.contains("previousVersion=1"));
    assertTrue(line.contains("newVersion=2"));
    assertTrue(line.contains("dispatch.max-attempts"));
    assertTrue(line.contains("requeue.interval-ms"));
    assertTrue(line.contains(LogFields.CORRELATION_ID + "=" + CORRELATION));
  }

  @Test
  void rejectedChangeLogsReasonAndKeysWithoutValuesAndWithCorrelation() {
    eventLogger.logOutcome(
        ConfigurationChangeOutcome.rejected(
            "provider.brevo.timeout-ms must be between 1000 and 60000",
            Set.of("provider.brevo.timeout-ms"),
            4),
        CORRELATION);

    assertEquals(1, count("CONFIG_REJECTED"));
    final String line = lines().get(0);
    assertTrue(line.contains("provider.brevo.timeout-ms must be between 1000 and 60000"));
    assertTrue(line.contains("currentVersion=4"));
    assertTrue(line.contains(LogFields.CORRELATION_ID + "=" + CORRELATION));
  }

  @Test
  void ignoredChangeLogsTheVersionOnceWhileTheSameStaleVersionKeepsArriving() {
    final ConfigurationChangeOutcome stale = ConfigurationChangeOutcome.ignoredStale(5, 5);

    eventLogger.logOutcome(stale, CORRELATION);
    eventLogger.logOutcome(stale, CORRELATION);
    eventLogger.logOutcome(stale, CORRELATION);

    assertEquals(1, count("CONFIG_IGNORED"));
    assertTrue(lines().get(0).contains("currentVersion=5"));
    assertTrue(lines().get(0).contains(LogFields.CORRELATION_ID + "=" + CORRELATION));
  }

  @Test
  void ignoredChangeIsLoggedAgainAfterAnotherEventBreaksTheRepetition() {
    final ConfigurationChangeOutcome stale = ConfigurationChangeOutcome.ignoredStale(5, 5);

    eventLogger.logOutcome(stale, CORRELATION);
    eventLogger.logOutcome(
        ConfigurationChangeOutcome.applied(Set.of("a"), Set.of(), 5, 6), CORRELATION);
    eventLogger.logOutcome(ConfigurationChangeOutcome.ignoredStale(6, 6), CORRELATION);

    assertEquals(2, count("CONFIG_IGNORED"));
    assertEquals(1, count("CONFIG_APPLIED"));
  }

  @Test
  void unavailableIsLoggedOnlyOnTheTransitionNotOnEveryFailedCycle() {
    final RuntimeException failure = new ParametersUnavailableException("down");

    eventLogger.logFailure(failure, CORRELATION);
    eventLogger.logFailure(failure, "param-second");
    eventLogger.logFailure(failure, "param-third");

    assertEquals(1, count("PARAMETERS_UNAVAILABLE"));
    assertEquals(0, count("PARAMETERS_RECOVERED"));
    assertTrue(lines().get(0).contains(LogFields.CORRELATION_ID + "=" + CORRELATION));
  }

  @Test
  void recoveredIsLoggedOnceWhenTheComponentAnswersAgainAndANewOutageLogsAgain() {
    final RuntimeException failure = new ParametersUnavailableException("down");
    final ConfigurationChangeOutcome stale = ConfigurationChangeOutcome.ignoredStale(1, 1);

    eventLogger.logFailure(failure, CORRELATION);
    eventLogger.logOutcome(stale, "param-recovery");
    eventLogger.logOutcome(stale, "param-after");

    assertEquals(1, count("PARAMETERS_UNAVAILABLE"));
    assertEquals(1, count("PARAMETERS_RECOVERED"));
    final String recovered =
        lines().stream()
            .filter(line -> line.contains("event=PARAMETERS_RECOVERED"))
            .findFirst()
            .orElseThrow();
    assertTrue(recovered.contains(LogFields.CORRELATION_ID + "=param-recovery"));

    eventLogger.logFailure(failure, "param-again");

    assertEquals(2, count("PARAMETERS_UNAVAILABLE"));
  }

  @Test
  void observeLogsTheOutcomeWithTheCorrelationOfTheReactorContextAndPassesItThrough() {
    final ConfigurationChangeOutcome outcome =
        ConfigurationChangeOutcome.applied(Set.of("dispatch.max-attempts"), Set.of(), 0, 1);

    StepVerifier.create(
            eventLogger
                .observe(Mono.just(outcome))
                .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "param-from-context")))
        .expectNext(outcome)
        .verifyComplete();

    assertEquals(1, count("CONFIG_APPLIED"));
    assertTrue(lines().get(0).contains(LogFields.CORRELATION_ID + "=param-from-context"));
  }

  @Test
  void observeGeneratesAParamPrefixedCorrelationWhenTheContextHasNone() {
    StepVerifier.create(
            eventLogger.observe(
                Mono.just(ConfigurationChangeOutcome.applied(Set.of("a"), Set.of(), 0, 1))))
        .expectNextCount(1)
        .verifyComplete();

    assertTrue(lines().get(0).contains(LogFields.CORRELATION_ID + "=param-"));
  }

  @Test
  void observeLogsUnavailableOnAFailedCycleAndRethrowsTheError() {
    StepVerifier.create(
            eventLogger
                .observe(Mono.error(new ParametersUnavailableException("down")))
                .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "param-failed")))
        .expectError(ParametersUnavailableException.class)
        .verify();

    assertEquals(1, count("PARAMETERS_UNAVAILABLE"));
    assertTrue(lines().get(0).contains(LogFields.CORRELATION_ID + "=param-failed"));
  }

  @Test
  void noLineContainsCredentialValuesAndTheAppliedEventIsPresentInTheCapturedLog() {
    eventLogger.logOutcome(
        ConfigurationChangeOutcome.rejected(
            "unknown parameter key provider.brevo.api-key", Set.of("provider.brevo.api-key"), 1),
        CORRELATION);
    eventLogger.logOutcome(
        ConfigurationChangeOutcome.applied(Set.of("dispatch.max-attempts"), Set.of(), 1, 2),
        CORRELATION);
    eventLogger.logFailure(
        new ParametersUnavailableException("down " + "https://parametros.interno"), CORRELATION);

    assertEquals(1, count("CONFIG_APPLIED"));
    assertEquals(3, lines().size());
    lines().forEach(line -> assertFalse(line.contains(CREDENTIAL)));
    lines().forEach(line -> assertFalse(line.contains("parametros.interno")));
  }
}
