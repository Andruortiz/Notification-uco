package co.edu.uco.notification.core.usecase;

import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.change;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.defaults;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.AdoptionMode;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome.Status;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ApplyConfigurationChangeServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T11:00:00Z"), ZoneOffset.UTC);
  private static final ParameterDescriptor RESTART_ONLY =
      ParameterDescriptor.global("test.restart-only", 5, 1, 10).withAdoption(AdoptionMode.RESTART);

  private final ParameterRegistry registry = new ParameterRegistry(List.of(RESTART_ONLY));
  private final ConfigurationHolder holder = new ConfigurationHolder(defaults(registry));
  private final ApplyConfigurationChangeService service =
      new ApplyConfigurationChangeService(
          holder, new ConfigurationValidator(registry), fixed(), CLOCK);

  private static ConfigurationSnapshot defaults(final ParameterRegistry registry) {
    return co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.defaults(
        registry);
  }

  @Test
  void validChangeIsAppliedAndRaisesTheVersion() {
    StepVerifier.create(service.apply(change(1, "dispatch.max-attempts", 5)))
        .assertNext(
            outcome -> {
              assertEquals(Status.APPLIED, outcome.status());
              assertEquals(0, outcome.previousVersion());
              assertEquals(1, outcome.newVersion());
              assertEquals(Set.of("dispatch.max-attempts"), outcome.keys());
            })
        .verifyComplete();

    final ConfigurationSnapshot current = holder.snapshot();
    assertEquals(1, current.version());
    assertEquals(5, current.dispatchMaxAttempts());
    assertEquals(ConfigurationSource.PARAMETERS, current.source());
    assertEquals(Instant.parse("2026-10-05T11:00:00Z"), current.adoptedAt());
    assertEquals(30_000, current.requeueIntervalMs());
  }

  @Test
  void invalidChangeIsRejectedWithReasonAndKeepsSnapshotAndValues() {
    final ConfigurationSnapshot before = holder.snapshot();

    StepVerifier.create(
            service.apply(change(1, "dispatch.max-attempts", 5, "requeue.interval-ms", 1)))
        .assertNext(
            outcome -> {
              assertEquals(Status.REJECTED, outcome.status());
              assertTrue(outcome.reason().contains("requeue.interval-ms"));
              assertEquals(0, outcome.previousVersion());
              assertEquals(0, outcome.newVersion());
              assertEquals(Set.of("dispatch.max-attempts", "requeue.interval-ms"), outcome.keys());
            })
        .verifyComplete();

    assertSame(before, holder.snapshot());
    assertEquals(3, holder.snapshot().dispatchMaxAttempts());

    StepVerifier.create(
            service.apply(change(1, "dispatch.max-attempts", 5, "requeue.interval-ms", 20_000)))
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();
    assertEquals(5, holder.snapshot().dispatchMaxAttempts());
    assertEquals(20_000, holder.snapshot().requeueIntervalMs());
  }

  @Test
  void crossParameterViolationRejectsTheWholeChange() {
    StepVerifier.create(service.apply(change(1, "requeue.interval-ms", 10_000)))
        .assertNext(
            outcome -> {
              assertEquals(Status.REJECTED, outcome.status());
              assertTrue(outcome.reason().contains("ProviderTimeoutBelowRequeueIntervalRule"));
            })
        .verifyComplete();

    assertEquals(0, holder.snapshot().version());
    assertEquals(30_000, holder.snapshot().requeueIntervalMs());
  }

  @Test
  void staleOrEqualVersionIsIgnoredAndGreaterOneIsApplied() {
    service.apply(change(5, "dispatch.max-attempts", 5)).block();
    final ConfigurationSnapshot afterFive = holder.snapshot();

    for (final long version : new long[] {5, 4, 0}) {
      StepVerifier.create(service.apply(change(version, "dispatch.max-attempts", 9)))
          .assertNext(
              outcome -> {
                assertEquals(Status.IGNORED_STALE, outcome.status());
                assertEquals(5, outcome.previousVersion());
                assertEquals(5, outcome.newVersion());
              })
          .verifyComplete();
      assertSame(afterFive, holder.snapshot());
    }

    StepVerifier.create(service.apply(change(6, "dispatch.max-attempts", 9)))
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();
    assertEquals(9, holder.snapshot().dispatchMaxAttempts());
    assertEquals(6, holder.snapshot().version());
  }

  @Test
  void aRevertPublishedAsANewerVersionIsApplied() {
    service.apply(change(1, "dispatch.max-attempts", 5)).block();

    StepVerifier.create(service.apply(change(2, "dispatch.max-attempts", 3)))
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();

    assertEquals(3, holder.snapshot().dispatchMaxAttempts());
    assertEquals(2, holder.snapshot().version());
  }

  @Test
  void restartDescriptorIsReportedPendingAndDoesNotChangeTheCurrentValue() {
    StepVerifier.create(service.apply(change(1, "test.restart-only", 8)))
        .assertNext(
            outcome -> {
              assertEquals(Status.PENDING_RESTART, outcome.status());
              assertEquals(Set.of("test.restart-only"), outcome.pendingRestartKeys());
              assertEquals(1, outcome.newVersion());
            })
        .verifyComplete();

    assertEquals(5, holder.snapshot().require("test.restart-only"));
    assertEquals(Set.of("test.restart-only"), holder.snapshot().pendingRestart());

    StepVerifier.create(service.apply(change(2, "dispatch.max-attempts", 7)))
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();
    assertEquals(7, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void aMixedChangeAppliesHotKeysAndListsRestartKeysAsPending() {
    StepVerifier.create(
            service.apply(change(1, "dispatch.max-attempts", 7, "test.restart-only", 8)))
        .assertNext(
            outcome -> {
              assertEquals(Status.APPLIED, outcome.status());
              assertEquals(Set.of("dispatch.max-attempts"), outcome.keys());
              assertEquals(Set.of("test.restart-only"), outcome.pendingRestartKeys());
            })
        .verifyComplete();

    assertEquals(7, holder.snapshot().dispatchMaxAttempts());
    assertEquals(5, holder.snapshot().require("test.restart-only"));
  }

  @Test
  void concurrentChangesAreProcessedInOrderAndNoUpdateIsLost() {
    for (int round = 0; round < 200; round++) {
      final ConfigurationHolder roundHolder = new ConfigurationHolder(defaults(registry));
      final ApplyConfigurationChangeService roundService =
          new ApplyConfigurationChangeService(
              roundHolder, new ConfigurationValidator(registry), fixed(), CLOCK);

      final Mono<ConfigurationChangeOutcome> first =
          Mono.defer(() -> roundService.apply(change(1, "dispatch.max-attempts", 5)))
              .subscribeOn(Schedulers.parallel());
      final Mono<ConfigurationChangeOutcome> second =
          Mono.defer(() -> roundService.apply(change(2, "requeue.interval-ms", 20_000)))
              .subscribeOn(Schedulers.parallel());

      final List<ConfigurationChangeOutcome> outcomes =
          Mono.zip(first, second).map(tuple -> List.of(tuple.getT1(), tuple.getT2())).block();

      final ConfigurationSnapshot finalSnapshot = roundHolder.snapshot();
      assertEquals(2, finalSnapshot.version());
      assertEquals(20_000, finalSnapshot.requeueIntervalMs());
      assertEquals(Status.APPLIED, outcomes.get(1).status());
      final boolean firstApplied = outcomes.get(0).status() == Status.APPLIED;
      assertTrue(firstApplied || outcomes.get(0).status() == Status.IGNORED_STALE);
      assertEquals(firstApplied ? 5 : 3, finalSnapshot.dispatchMaxAttempts());
    }
  }
}
