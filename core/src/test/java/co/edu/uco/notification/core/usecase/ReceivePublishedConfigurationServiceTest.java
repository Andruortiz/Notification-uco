package co.edu.uco.notification.core.usecase;

import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.change;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.defaults;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome.Status;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReceivePublishedConfigurationServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

  private final ParameterRegistry registry = new ParameterRegistry();
  private final ConfigurationHolder holder = new ConfigurationHolder(defaults(registry));
  private final List<ConfigurationSnapshot> saved = new ArrayList<>();
  private final AtomicBoolean storeFails = new AtomicBoolean(false);

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

  private final ReceivePublishedConfigurationService service =
      new ReceivePublishedConfigurationService(
          new ApplyConfigurationChangeService(
              holder, new ConfigurationValidator(registry), fixed(), CLOCK),
          lastKnown,
          holder);

  @Test
  void aValidChangeIsAppliedAndPersistedWithTheVersionNowInForce() {
    StepVerifier.create(service.receive(change(4, "dispatch.max-attempts", 5)))
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();

    assertEquals(5, holder.snapshot().dispatchMaxAttempts());
    assertEquals(1, saved.size());
    assertEquals(4, saved.get(0).version());
  }

  @Test
  void aRejectedChangeIsNotPersistedAndKeepsTheCurrentVersion() {
    StepVerifier.create(service.receive(change(4, "dispatch.max-attempts", 99)))
        .assertNext(outcome -> assertEquals(Status.REJECTED, outcome.status()))
        .verifyComplete();

    assertTrue(saved.isEmpty());
    assertEquals(0, holder.snapshot().version());
    assertEquals(3, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void aStaleOrDuplicatedVersionIsIgnoredAndNotPersistedAgain() {
    StepVerifier.create(service.receive(change(4, "dispatch.max-attempts", 5)))
        .expectNextCount(1)
        .verifyComplete();
    saved.clear();

    StepVerifier.create(service.receive(change(4, "dispatch.max-attempts", 9)))
        .assertNext(outcome -> assertEquals(Status.IGNORED_STALE, outcome.status()))
        .verifyComplete();
    StepVerifier.create(service.receive(change(2, "dispatch.max-attempts", 9)))
        .assertNext(outcome -> assertEquals(Status.IGNORED_STALE, outcome.status()))
        .verifyComplete();

    assertTrue(saved.isEmpty());
    assertEquals(5, holder.snapshot().dispatchMaxAttempts());
    assertEquals(4, holder.snapshot().version());
  }

  @Test
  void aFailingStoreDoesNotUndoTheAdoptedConfiguration() {
    storeFails.set(true);

    StepVerifier.create(service.receive(change(1, "dispatch.max-attempts", 5)))
        .assertNext(outcome -> assertEquals(Status.APPLIED, outcome.status()))
        .verifyComplete();

    assertEquals(5, holder.snapshot().dispatchMaxAttempts());
    assertEquals(1, holder.snapshot().version());
  }

  @Test
  void aNullChangeIsRejectedByThePrecondition() {
    assertThrows(RuntimeException.class, () -> service.receive(null));
  }
}
