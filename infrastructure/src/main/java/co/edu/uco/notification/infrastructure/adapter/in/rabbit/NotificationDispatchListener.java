package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// Spring AMQP's listener container is imperative, not reactive, so the use case's Mono is
// blocked on here to bridge the two -- reactor-rabbitmq would avoid this but isn't a dependency
// yet (a build-vs-buy call, not made for this walking skeleton). A failed dispatch() propagates
// as an exception, which the default listener error handling requeues; no dead-letter queue or
// poison-message handling exists yet (documented gap, same as the rest of the retry story).
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
