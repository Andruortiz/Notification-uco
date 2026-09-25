package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import java.util.Optional;
import reactor.core.publisher.Mono;

public interface NotificationSenderPort {

  Mono<AttemptResult> send(Notification notification);

  ProviderId providerId();

  Optional<String> disabledReason();
}
