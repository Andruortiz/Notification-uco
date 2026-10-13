package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.core.usecase.ConfigurationHolder;
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.SimpleTriggerContext;
import reactor.core.publisher.Mono;

class PendingNotificationSchedulerAdapterTest {

  private static ConfigurationSnapshot snapshot(final long version, final long intervalMs) {
    return new ConfigurationSnapshot(
        version,
        ConfigurationSource.DEFAULTS,
        Map.of(ParameterRegistry.REQUEUE_INTERVAL_MS, intervalMs),
        Instant.now(),
        Set.of());
  }

  private static PendingNotificationSchedulerAdapter adapter(
      final RequeuePendingNotificationsUseCase useCase, final ConfigurationHolder holder) {
    return new PendingNotificationSchedulerAdapter(useCase, holder);
  }

  @Test
  void eachRunCarriesAFreshSchedulerCorrelationIdThatIsValid() {
    final List<String> seen = new ArrayList<>();
    final RequeuePendingNotificationsUseCase useCase =
        mock(RequeuePendingNotificationsUseCase.class);
    when(useCase.requeuePending())
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  seen.add(context.get(CorrelationId.CONTEXT_KEY));
                  return Mono.empty();
                }));
    final PendingNotificationSchedulerAdapter adapter =
        adapter(useCase, new ConfigurationHolder(snapshot(0, 30_000)));

    adapter.requeuePendingNotifications();
    adapter.requeuePendingNotifications();

    assertTrue(seen.size() == 2);
    seen.forEach(
        id -> {
          assertTrue(id.startsWith("sched-"));
          assertDoesNotThrow(() -> CorrelationId.of(id));
        });
    assertNotEquals(seen.get(0), seen.get(1));
  }

  @Test
  void constructorRejectsNullArguments() {
    final ConfigurationHolder holder = new ConfigurationHolder(snapshot(0, 30_000));
    final RequeuePendingNotificationsUseCase useCase =
        mock(RequeuePendingNotificationsUseCase.class);

    assertThrows(
        NullPointerException.class, () -> new PendingNotificationSchedulerAdapter(null, holder));
    assertThrows(
        NullPointerException.class, () -> new PendingNotificationSchedulerAdapter(useCase, null));
  }

  @Test
  void theNextCycleIsScheduledWithTheIntervalOfTheSnapshotCurrentWhenTheCycleEnds() {
    final ConfigurationHolder holder = new ConfigurationHolder(snapshot(0, 30_000));
    final PendingNotificationSchedulerAdapter adapter =
        adapter(mock(RequeuePendingNotificationsUseCase.class), holder);
    final Instant completion = Instant.parse("2026-10-05T10:00:00Z");

    assertEquals(completion.plusMillis(30_000), adapter.nextExecution(completion));
    assertEquals(completion.plusMillis(30_000), adapter.nextExecution(completion));

    holder.replace(snapshot(1, 45_000));

    assertEquals(completion.plusMillis(45_000), adapter.nextExecution(completion));
  }

  @Test
  void theFirstCycleRunsWithoutWaitingForTheInterval() {
    final PendingNotificationSchedulerAdapter adapter =
        adapter(
            mock(RequeuePendingNotificationsUseCase.class),
            new ConfigurationHolder(snapshot(0, 600_000)));

    final Instant before = Instant.now();
    final Instant first = adapter.nextExecution(null);

    assertTrue(Duration.between(before, first).abs().compareTo(Duration.ofSeconds(5)) < 0);
  }

  @Test
  void registersOneTriggerTaskThatFollowsTheCurrentInterval() {
    final ConfigurationHolder holder = new ConfigurationHolder(snapshot(0, 30_000));
    final PendingNotificationSchedulerAdapter adapter =
        adapter(mock(RequeuePendingNotificationsUseCase.class), holder);
    final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

    adapter.configureTasks(registrar);

    assertEquals(1, registrar.getTriggerTaskList().size());
    final TriggerTask task = registrar.getTriggerTaskList().get(0);
    final Instant completion = Instant.parse("2026-10-05T10:00:00Z");
    final SimpleTriggerContext context =
        new SimpleTriggerContext(completion, completion, completion);
    assertEquals(completion.plusMillis(30_000), task.getTrigger().nextExecution(context));
    holder.replace(snapshot(1, 5_000));
    assertEquals(completion.plusMillis(5_000), task.getTrigger().nextExecution(context));
  }
}
