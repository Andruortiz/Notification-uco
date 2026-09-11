package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationDispatchListener {

  private final DispatchNotificationUseCase dispatchNotificationUseCase;

  public NotificationDispatchListener(
      final DispatchNotificationUseCase dispatchNotificationUseCase) {
    this.dispatchNotificationUseCase =
        Preconditions.requireNonNull(
            dispatchNotificationUseCase, "dispatchNotificationUseCase must not be null");
  }

  @RabbitListener(queues = "${notification.rabbit.dispatch.queue}")
  public void onMessage(final String notificationId) {
    dispatchNotificationUseCase.dispatch(NotificationId.of(notificationId)).block();
  }
}
