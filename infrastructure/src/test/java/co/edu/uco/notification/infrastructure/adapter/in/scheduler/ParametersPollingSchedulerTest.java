package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import static co.edu.uco.notification.infrastructure.support.ConfigurationTestData.change;
import static co.edu.uco.notification.infrastructure.support.ConfigurationTestData.defaults;
import static co.edu.uco.notification.infrastructure.support.ConfigurationTestData.fixed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.exception.ParametersUnavailableException;
import co.edu.uco.notification.core.port.in.SynchronizeConfigurationUseCase;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import co.edu.uco.notification.core.usecase.ApplyConfigurationChangeService;
import co.edu.uco.notification.core.usecase.ConfigurationHolder;
import co.edu.uco.notification.core.usecase.SynchronizeConfigurationService;
import co.edu.uco.notification.infrastructure.adapter.out.parameters.NoParametersSource;
import co.edu.uco.notification.infrastructure.adapter.out.parameters.ParametersProperties;
import co.edu.uco.notification.infrastructure.config.ParametersConfig;
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ParametersPollingSchedulerTest {

  private static ConfigurationChangeOutcome applied() {
    return ConfigurationChangeOutcome.applied(java.util.Set.of(), java.util.Set.of(), 0, 1);
  }

  @Test
  void everyPollInvokesTheSynchronizationWithAFreshParamCorrelationId() {
    final List<String> seen = new ArrayList<>();
    final SynchronizeConfigurationUseCase useCase = mock(SynchronizeConfigurationUseCase.class);
    when(useCase.synchronize())
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  seen.add(context.get(CorrelationId.CONTEXT_KEY));
                  return Mono.just(applied());
                }));
    final ParametersPollingScheduler scheduler = new ParametersPollingScheduler(useCase);

    scheduler.poll();
    scheduler.poll();

    assertEquals(2, seen.size());
    seen.forEach(
        id -> {
          assertTrue(id.startsWith("param-"));
          assertDoesNotThrow(() -> CorrelationId.of(id));
        });
    assertNotEquals(seen.get(0), seen.get(1));
  }

  @Test
  void aFailedCycleDoesNotStopThePollingAndTheNextCycleAppliesTheChange() {
    final ParameterRegistry registry = new ParameterRegistry();
    final ConfigurationHolder holder = new ConfigurationHolder(defaults());
    final AtomicReference<Mono<ConfigurationChange>> state =
        new AtomicReference<>(Mono.error(new ParametersUnavailableException("down")));
    final AtomicInteger calls = new AtomicInteger();
    final ParametersSourcePort source =
        () -> {
          calls.incrementAndGet();
          return state.get();
        };
    final LastKnownConfigurationPort lastKnown =
        new LastKnownConfigurationPort() {
          @Override
          public Mono<co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot>
              load() {
            return Mono.empty();
          }

          @Override
          public Mono<Boolean> saveIfNewer(
              final co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot
                  snapshot) {
            return Mono.just(true);
          }
        };
    final SynchronizeConfigurationService service =
        new SynchronizeConfigurationService(
            source,
            new ApplyConfigurationChangeService(
                holder, new ConfigurationValidator(registry), fixed(), Clock.systemUTC()),
            lastKnown,
            holder);
    final ParametersPollingScheduler scheduler = new ParametersPollingScheduler(service);

    assertDoesNotThrow(scheduler::poll);
    assertEquals(1, calls.get());
    assertEquals(0, holder.snapshot().version());
    assertEquals(ConfigurationSource.DEFAULTS, holder.snapshot().source());

    state.set(Mono.just(change(1, "dispatch.max-attempts", 5)));
    scheduler.poll();

    assertEquals(2, calls.get());
    assertEquals(1, holder.snapshot().version());
    assertEquals(5, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void anUnexpectedFailureInACycleIsAbsorbed() {
    final SynchronizeConfigurationUseCase useCase = mock(SynchronizeConfigurationUseCase.class);
    when(useCase.synchronize()).thenReturn(Mono.error(new IllegalStateException("bug")));

    assertDoesNotThrow(() -> new ParametersPollingScheduler(useCase).poll());
  }

  @Test
  void withoutABaseUrlTheSourceIsNoParametersSourceAndTheServiceOperatesWithDefaults() {
    new ApplicationContextRunner()
        .withUserConfiguration(ParametersConfig.class)
        .run(
            context -> {
              final ParametersSourcePort source = context.getBean(ParametersSourcePort.class);
              assertInstanceOf(NoParametersSource.class, source);
              StepVerifier.create(source.fetchState()).verifyComplete();
            });

    final ParameterRegistry registry = new ParameterRegistry();
    final ConfigurationHolder holder = new ConfigurationHolder(defaults());
    final SynchronizeConfigurationService service =
        new SynchronizeConfigurationService(
            new NoParametersSource(),
            new ApplyConfigurationChangeService(
                holder, new ConfigurationValidator(registry), fixed(), Clock.systemUTC()),
            new LastKnownConfigurationPort() {
              @Override
              public Mono<co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot>
                  load() {
                return Mono.empty();
              }

              @Override
              public Mono<Boolean> saveIfNewer(
                  final co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot
                      snapshot) {
                return Mono.just(true);
              }
            },
            holder);

    StepVerifier.create(service.synchronize()).verifyComplete();
    assertEquals(0, holder.snapshot().version());
    assertEquals(3, holder.snapshot().dispatchMaxAttempts());
  }

  @Test
  void withABaseUrlTheSourceIsTheHttpAdapterControlForTheDefaultOne() {
    new ApplicationContextRunner()
        .withUserConfiguration(ParametersConfig.class)
        .withPropertyValues("notification.parameters.base-url=http://127.0.0.1:1")
        .run(
            context -> {
              assertEquals(
                  "HttpParametersSource",
                  context.getBean(ParametersSourcePort.class).getClass().getSimpleName());
              assertTrue(context.getBean(ParametersProperties.class).hasSource());
            });
  }

  @Test
  void constructorRejectsNullUseCase() {
    assertThrows(NullPointerException.class, () -> new ParametersPollingScheduler(null));
  }
}
