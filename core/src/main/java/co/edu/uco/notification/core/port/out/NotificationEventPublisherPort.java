package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import java.util.List;
import reactor.core.publisher.Mono;

public interface NotificationEventPublisherPort {

  Mono<Void> enqueueForDispatch(Notification notification);

  Mono<Void> publish(List<DomainEvent> events);
}
