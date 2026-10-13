package co.edu.uco.notification.core.usecase;

import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.change;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.defaults;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome.Status;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.exception.ParametersUnavailableException;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SynchronizeConfigurationServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

  private final ParameterRegistry registry = new ParameterRegistry();
  private final ConfigurationHolder holder = new ConfigurationHolder(defaults(registry));
  private final AtomicReference<Mono<ConfigurationChange>> state =
      new AtomicReference<>(Mono.empty());
  private final List<ConfigurationSnapshot> saved = new ArrayList<>();
  private final AtomicBoolean storeFails = new AtomicBoolean(false);

  private final ParametersSourcePort source = () -> Mono.defer(state::get);
  private final LastKnownConfigurationPort lastKnown =
      new LastKnownConfigurationPort() {
        @Override
        public Mono<ConfigurationSnapshot> load() {
          return Mono.empty();
        }

        @Override
        public Mono<Boolean> saveIfNewer(final ConfigurationSnapshot snapshot) {
          if (storeFails.get()) {
            return Mono.error(new IllegalStateException("store down"));
          }
          saved.add(snapshot);
          return Mono.just(true);
        }
      };

  private final SynchronizeConfigurationService service =
      new SynchronizeConfigurationService(
          source,
          new ApplyConfigurationChangeService(
              holder, new ConfigurationValidator(registry), fixed(), CLOCK),
          lastKnown,
          holder);

  @Test
  void anAppliedStateIsPersistedWithTheVersionNowInForce() {
    state.set(Mono.just(change(1, "dispatch.max-attempts", 5)));

    StepVerifier.create(service.synchronize())
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();

    assertEquals(5, holder.snapshot().dispatchMaxAttempts());
    assertEquals(1, saved.size());
    assertEquals(1, saved.get(0).version());
    assertEquals(5, saved.get(0).dispatchMaxAttempts());
  }

  @Test
  void aRejectedStateIsNotPersistedAndKeepsTheCurrentVersion() {
    state.set(Mono.just(change(1, "dispatch.max-attempts", 99)));

    StepVerifier.create(service.synchronize())
        .assertNext(outcome -> assertEquals(Status.REJECTED, outcome.status()))
        .verifyComplete();

    assertTrue(saved.isEmpty());
    assertEquals(0, holder.snapshot().version());
  }

  @Test
  void aStaleStateIsNotPersistedWhileTheFirstOneWas() {
    state.set(Mono.just(change(2, "dispatch.max-attempts", 4)));
    StepVerifier.create(service.synchronize()).expectNextCount(1).verifyComplete();
    assertEquals(1, saved.size());
    saved.clear();

    state.set(Mono.just(change(2, "dispatch.max-attempts", 6)));
    StepVerifier.create(service.synchronize())
        .assertNext(outcome -> assertEquals(Status.IGNORED_STALE, outcome.status()))
        .verifyComplete();

    assertTrue(saved.isEmpty());
    assertEquals(4, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void anUnavailableSourceKeepsTheSnapshotAndPersistsNothingThenTheNextCycleApplies() {
    state.set(Mono.error(new ParametersUnavailableException("down")));

    StepVerifier.create(service.synchronize())
        .expectError(ParametersUnavailableException.class)
        .verify();

    assertTrue(saved.isEmpty());
    assertEquals(0, holder.snapshot().version());
    assertEquals(3, holder.snapshot().dispatchMaxAttempts());

    state.set(Mono.just(change(1, "dispatch.max-attempts", 5)));
    StepVerifier.create(service.synchronize())
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();

    assertEquals(1, holder.snapshot().version());
    assertEquals(1, saved.size());
  }

  @Test
  void aSourceWithNothingToPublishCompletesEmptyAndChangesNothing() {
    StepVerifier.create(service.synchronize()).verifyComplete();

    assertTrue(saved.isEmpty());
    assertEquals(0, holder.snapshot().version());
  }

  @Test
  void aFailingStoreDoesNotUndoTheAdoptedConfiguration() {
    storeFails.set(true);
    state.set(Mono.just(change(1, "dispatch.max-attempts", 5)));

    StepVerifier.create(service.synchronize())
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();

    assertEquals(5, holder.snapshot().dispatchMaxAttempts());
    assertEquals(1, holder.snapshot().version());
  }
}
