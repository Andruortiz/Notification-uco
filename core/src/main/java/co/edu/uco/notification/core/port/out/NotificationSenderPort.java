package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import java.util.Optional;
import reactor.core.publisher.Mono;

public interface NotificationSenderPort {

  Mono<ProviderResult> send(Notification notification);

  ProviderId providerId();

  Optional<String> disabledReason();

  boolean supportsAttachments();
}
