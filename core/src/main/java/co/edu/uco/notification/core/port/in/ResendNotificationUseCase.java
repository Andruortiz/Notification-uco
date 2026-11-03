package co.edu.uco.notification.core.port.in;

import reactor.core.publisher.Mono;

public interface ResendNotificationUseCase {

  Mono<NotificationStatusView> resend(ResendNotificationCommand command);
}
