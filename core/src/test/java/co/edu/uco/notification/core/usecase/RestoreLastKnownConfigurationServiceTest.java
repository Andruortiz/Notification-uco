package co.edu.uco.notification.core.usecase;

import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.defaults;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RestoreLastKnownConfigurationServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
  private static final Duration LOAD_TIMEOUT = Duration.ofMillis(200);

  private final ParameterRegistry registry = new ParameterRegistry();
  private final ConfigurationHolder holder = new ConfigurationHolder(defaults(registry));

  private RestoreLastKnownConfigurationService serviceLoading(
      final Mono<ConfigurationSnapshot> load) {
    return new RestoreLastKnownConfigurationService(
        new StubPort(load),
        holder,
        new ConfigurationValidator(registry),
        fixed(),
        CLOCK,
        LOAD_TIMEOUT);
  }

  private ConfigurationSnapshot stored(final long version, final Map<String, Long> overrides) {
    final Map<String, Long> values = new HashMap<>(defaults(registry).values());
    values.putAll(overrides);
    return new ConfigurationSnapshot(
        version,
        ConfigurationSource.PARAMETERS,
        values,
        Instant.parse("2026-10-04T00:00:00Z"),
        Set.of());
  }

  @Test
  void aValidLastKnownConfigurationIsAdoptedWithTheLastKnownSource() {
    final ConfigurationSnapshot saved = stored(12, Map.of("dispatch.max-attempts", 7L));

    StepVerifier.create(serviceLoading(Mono.just(saved)).restore())
        .assertNext(
            snapshot -> {
              assertEquals(ConfigurationSource.LAST_KNOWN, snapshot.source());
              assertEquals(12, snapshot.version());
              assertEquals(7, snapshot.dispatchMaxAttempts());
            })
        .verifyComplete();

    assertEquals(ConfigurationSource.LAST_KNOWN, holder.snapshot().source());
    assertEquals(12, holder.snapshot().version());
    assertEquals(7, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void anAbsentLastKnownConfigurationKeepsTheDefaults() {
    StepVerifier.create(serviceLoading(Mono.empty()).restore())
        .assertNext(snapshot -> assertEquals(ConfigurationSource.DEFAULTS, snapshot.source()))
        .verifyComplete();

    assertEquals(0, holder.snapshot().version());
    assertEquals(3, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void aLastKnownConfigurationThatFailsTheValidatorIsDiscardedAndTheDefaultsRemain() {
    final ConfigurationSnapshot invalid = stored(12, Map.of("dispatch.max-attempts", 99L));

    StepVerifier.create(serviceLoading(Mono.just(invalid)).restore())
        .assertNext(snapshot -> assertEquals(ConfigurationSource.DEFAULTS, snapshot.source()))
        .verifyComplete();

    assertEquals(0, holder.snapshot().version());
    assertEquals(3, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void aLastKnownConfigurationWithAnUnknownKeyIsDiscarded() {
    final Map<String, Long> values = new HashMap<>(defaults(registry).values());
    values.put("legacy.removed-key", 1L);
    final ConfigurationSnapshot withUnknownKey =
        new ConfigurationSnapshot(
            12,
            ConfigurationSource.PARAMETERS,
            values,
            Instant.parse("2026-10-04T00:00:00Z"),
            Set.of());

    StepVerifier.create(serviceLoading(Mono.just(withUnknownKey)).restore())
        .assertNext(snapshot -> assertEquals(ConfigurationSource.DEFAULTS, snapshot.source()))
        .verifyComplete();
  }

  @Test
  void aLastKnownConfigurationMissingAKeyIsDiscarded() {
    final Map<String, Long> values = new HashMap<>(defaults(registry).values());
    values.remove("requeue.interval-ms");
    final ConfigurationSnapshot incomplete =
        new ConfigurationSnapshot(
            12,
            ConfigurationSource.PARAMETERS,
            values,
            Instant.parse("2026-10-04T00:00:00Z"),
            Set.of());

    StepVerifier.create(serviceLoading(Mono.just(incomplete)).restore())
        .assertNext(snapshot -> assertEquals(ConfigurationSource.DEFAULTS, snapshot.source()))
        .verifyComplete();
  }

  @Test
  void aStoreThatFailsIsAbsorbedAndTheDefaultsRemain() {
    StepVerifier.create(
            serviceLoading(Mono.error(new IllegalStateException("store down"))).restore())
        .assertNext(snapshot -> assertEquals(ConfigurationSource.DEFAULTS, snapshot.source()))
        .verifyComplete();

    assertEquals(0, holder.snapshot().version());
  }

  @Test
  void aStoreThatExceedsTheLoadLimitIsAbsorbedAndTheDefaultsRemain() {
    final Mono<ConfigurationSnapshot> slow =
        Mono.just(stored(12, Map.of())).delayElement(Duration.ofSeconds(5));

    StepVerifier.create(serviceLoading(slow).restore())
        .assertNext(snapshot -> assertEquals(ConfigurationSource.DEFAULTS, snapshot.source()))
        .expectComplete()
        .verify(Duration.ofSeconds(3));

    assertEquals(0, holder.snapshot().version());
  }

  @Test
  void aStoreThatRespondsInsideTheLimitIsAdoptedControlForTheSlowStore() {
    final Mono<ConfigurationSnapshot> quick =
        Mono.just(stored(12, Map.of())).delayElement(Duration.ofMillis(20));

    StepVerifier.create(serviceLoading(quick).restore())
        .assertNext(snapshot -> assertEquals(ConfigurationSource.LAST_KNOWN, snapshot.source()))
        .verifyComplete();
  }

  private record StubPort(Mono<ConfigurationSnapshot> load) implements LastKnownConfigurationPort {

    @Override
    public Mono<Boolean> saveIfNewer(final ConfigurationSnapshot snapshot) {
      return Mono.just(true);
    }
  }
}
