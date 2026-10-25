package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import reactor.core.publisher.Mono;

public interface DispatchNotificationUseCase {

  Mono<Void> dispatch(NotificationId notificationId, AttemptOrigin origin);

  default Mono<Void> dispatch(final NotificationId notificationId) {
    return dispatch(notificationId, AttemptOrigin.AUTOMATIC);
  }
}
