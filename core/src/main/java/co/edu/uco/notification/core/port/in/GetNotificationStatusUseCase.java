package co.edu.uco.notification.core.port.in;

import reactor.core.publisher.Mono;

public interface GetNotificationStatusUseCase {

  Mono<NotificationStatusView> getStatus(GetNotificationStatusQuery query);
}
