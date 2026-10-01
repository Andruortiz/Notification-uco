package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.utils.CorrelationId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PendingNotificationSchedulerAdapterTest {

  @Test
  void eachRunCarriesAFreshSchedulerCorrelationIdThatIsValid() {
    final List<String> seen = new ArrayList<>();
    final RequeuePendingNotificationsUseCase useCase =
        mock(RequeuePendingNotificationsUseCase.class);
    when(useCase.requeuePending())
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  seen.add(context.get(CorrelationId.CONTEXT_KEY));
                  return Mono.empty();
                }));
    final PendingNotificationSchedulerAdapter adapter =
        new PendingNotificationSchedulerAdapter(useCase);

    adapter.requeuePendingNotifications();
    adapter.requeuePendingNotifications();

    assertTrue(seen.size() == 2);
    seen.forEach(
        id -> {
          assertTrue(id.startsWith("sched-"));
          assertDoesNotThrow(() -> CorrelationId.of(id));
        });
    assertNotEquals(seen.get(0), seen.get(1));
  }

  @Test
  void constructorRejectsNullUseCase() {
    assertThrows(NullPointerException.class, () -> new PendingNotificationSchedulerAdapter(null));
  }
}
