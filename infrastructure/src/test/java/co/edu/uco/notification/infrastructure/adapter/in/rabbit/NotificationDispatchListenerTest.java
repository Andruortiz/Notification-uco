package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class NotificationDispatchListenerTest {

  @Test
  void onMessageDispatchesTheNotificationWithTheGivenId() {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    when(useCase.dispatch(eq(id))).thenReturn(Mono.empty());
    final NotificationDispatchListener listener = new NotificationDispatchListener(useCase);

    listener.onMessage(id.value());

    verify(useCase).dispatch(id);
  }

  @Test
  void onMessagePropagatesAFailedDispatchAsAnException() {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(new NotificationNotFoundException(id)));
    final NotificationDispatchListener listener = new NotificationDispatchListener(useCase);

    assertThrows(NotificationNotFoundException.class, () -> listener.onMessage(id.value()));
  }

  @Test
  void constructorRejectsNullUseCase() {
    assertThrows(NullPointerException.class, () -> new NotificationDispatchListener(null));
  }
}
