package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;

@Component
public class PendingNotificationSchedulerAdapter implements SchedulingConfigurer {

  private final RequeuePendingNotificationsUseCase requeuePendingNotificationsUseCase;
  private final ConfigurationView configurationView;

  public PendingNotificationSchedulerAdapter(
      RequeuePendingNotificationsUseCase requeuePendingNotificationsUseCase,
      ConfigurationView configurationView) {
    this.requeuePendingNotificationsUseCase =
        Preconditions.requireNonNull(
            requeuePendingNotificationsUseCase,
            "requeuePendingNotificationsUseCase must not be null");
    this.configurationView =
        Preconditions.requireNonNull(configurationView, "configurationView must not be null");
  }

  @Override
  public void configureTasks(final ScheduledTaskRegistrar taskRegistrar) {
    taskRegistrar.addTriggerTask(
        this::requeuePendingNotifications,
        triggerContext -> nextExecution(triggerContext.lastCompletion()));
  }

  Instant nextExecution(final Instant lastCompletion) {
    if (lastCompletion == null) {
      return Instant.now();
    }
    return lastCompletion.plusMillis(configurationView.snapshot().requeueIntervalMs());
  }

  public void requeuePendingNotifications() {
    requeuePendingNotificationsUseCase
        .requeuePending()
        .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "sched-" + UUID.randomUUID()))
        .subscribe();
  }
}
