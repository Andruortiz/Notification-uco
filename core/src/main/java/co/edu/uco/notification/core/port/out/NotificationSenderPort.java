package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.AttemptResult;
import co.edu.uco.notification.core.domain.Notification;
import reactor.core.publisher.Mono;

public interface NotificationSenderPort {

  Mono<AttemptResult> send(Notification notification);
}
