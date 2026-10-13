package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import reactor.core.publisher.Flux;

public interface NotificationUpdatesPort {

  Flux<NotificationId> updates();
}
