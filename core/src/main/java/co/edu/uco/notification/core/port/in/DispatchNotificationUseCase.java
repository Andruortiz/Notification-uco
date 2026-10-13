package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import reactor.core.publisher.Mono;

public interface DispatchNotificationUseCase {

  Mono<Void> dispatch(NotificationId notificationId);
}
