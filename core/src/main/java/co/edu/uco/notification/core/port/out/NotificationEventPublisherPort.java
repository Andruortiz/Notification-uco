package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import java.util.List;
import reactor.core.publisher.Mono;

public interface NotificationEventPublisherPort {

  Mono<Void> enqueueForDispatch(Notification notification, AttemptOrigin origin);

  Mono<Void> publish(List<DomainEvent> events);
}
