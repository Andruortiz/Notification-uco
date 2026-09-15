package co.edu.uco.notification.core.port.in;

import reactor.core.publisher.Flux;

public interface SubscribeToNotificationUpdatesUseCase {

  Flux<NotificationLiveUpdate> subscribe(SubscribeToNotificationUpdatesQuery query);
}
