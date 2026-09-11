package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PendingNotificationSchedulerAdapter {

  private final RequeuePendingNotificationsUseCase requeuePendingNotificationsUseCase;

  public PendingNotificationSchedulerAdapter(
      RequeuePendingNotificationsUseCase requeuePendingNotificationsUseCase) {
    this.requeuePendingNotificationsUseCase =
        Preconditions.requireNonNull(
            requeuePendingNotificationsUseCase,
            "requeuePendingNotificationsUseCase must not be null");
  }

  @Scheduled(fixedDelayString = "${notification.scheduler.requeue-interval-ms:30000}")
  public void requeuePendingNotifications() {
    requeuePendingNotificationsUseCase.requeuePending().subscribe();
  }
}
