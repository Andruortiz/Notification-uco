package co.edu.uco.notification.core.port.in;

import reactor.core.publisher.Mono;

public interface RetryNotificationUseCase {

  Mono<NotificationStatusView> retry(RetryNotificationCommand command);
}
