package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.infrastructure.config.LogFields;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;

class NotificationDispatchListenerTest {

  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private record Observed(
      String contextCorrelationId,
      String contextNotificationId,
      String contextTraceparent,
      String mdcCorrelationId,
      String mdcNotificationId,
      String mdcTraceparent) {}

  private static DispatchNotificationUseCase observing(
      final NotificationId id, final AtomicReference<Observed> observed) {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    when(useCase.dispatch(eq(id)))
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  observed.set(
                      new Observed(
                          context.getOrDefault(LogFields.CORRELATION_ID, null),
                          context.getOrDefault(LogFields.NOTIFICATION_ID, null),
                          context.getOrDefault(LogFields.TRACE_PARENT, null),
                          MDC.get(LogFields.CORRELATION_ID),
                          MDC.get(LogFields.NOTIFICATION_ID),
                          MDC.get(LogFields.TRACE_PARENT)));
                  return Mono.empty();
                }));
    return useCase;
  }

  @Test
  void onMessageDispatchesTheNotificationWithTheGivenId() {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    when(useCase.dispatch(eq(id))).thenReturn(Mono.empty());
    final NotificationDispatchListener listener = new NotificationDispatchListener(useCase);

    listener.onMessage(id.value(), "corr-1", null);

    verify(useCase).dispatch(id);
  }

  @Test
  void aValidHeaderBecomesTheCorrelationIdInTheContextAndTheMdcDuringTheDispatch() {
    final NotificationId id = NotificationId.newId();
    final AtomicReference<Observed> observed = new AtomicReference<>();
    final NotificationDispatchListener listener =
        new NotificationDispatchListener(observing(id, observed));

    listener.onMessage(id.value(), "corr-header", TRACEPARENT);

    final Observed seen = observed.get();
    assertEquals("corr-header", seen.contextCorrelationId());
    assertEquals(id.value(), seen.contextNotificationId());
    assertEquals(TRACEPARENT, seen.contextTraceparent());
    assertEquals("corr-header", seen.mdcCorrelationId());
    assertEquals(id.value(), seen.mdcNotificationId());
    assertEquals(TRACEPARENT, seen.mdcTraceparent());
  }

  @Test
  void aMissingHeaderFallsBackToALegacyPrefixedId() {
    final NotificationId id = NotificationId.newId();
    final AtomicReference<Observed> observed = new AtomicReference<>();
    final NotificationDispatchListener listener =
        new NotificationDispatchListener(observing(id, observed));

    listener.onMessage(id.value(), null, null);

    assertNotNull(observed.get().contextCorrelationId());
    assertTrue(
        observed
            .get()
            .contextCorrelationId()
            .startsWith(NotificationDispatchListener.LEGACY_PREFIX));
    assertEquals(observed.get().contextCorrelationId(), observed.get().mdcCorrelationId());
    assertNull(observed.get().contextTraceparent());
  }

  @Test
  void anInvalidHeaderIsNeverPropagatedAndFallsBackToALegacyId() {
    final NotificationId id = NotificationId.newId();
    final AtomicReference<Observed> observed = new AtomicReference<>();
    final NotificationDispatchListener listener =
        new NotificationDispatchListener(observing(id, observed));

    listener.onMessage(id.value(), "bad id\nforged", "garbage");

    assertTrue(observed.get().contextCorrelationId().startsWith("legacy-"));
    assertNull(observed.get().contextTraceparent());
    assertNull(observed.get().mdcTraceparent());
  }

  @Test
  void theMdcIsRestoredAfterTheMessageWhetherItSucceedsOrFails() {
    final NotificationId id = NotificationId.newId();
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(new NotificationNotFoundException(id)));
    final NotificationDispatchListener listener = new NotificationDispatchListener(useCase);

    assertThrows(
        NotificationNotFoundException.class, () -> listener.onMessage(id.value(), "corr-x", null));

    assertNull(MDC.get(LogFields.CORRELATION_ID));
    assertNull(MDC.get(LogFields.NOTIFICATION_ID));
    assertNull(MDC.get(LogFields.TRACE_PARENT));

    final AtomicReference<Observed> observed = new AtomicReference<>();
    final NotificationId other = NotificationId.newId();
    new NotificationDispatchListener(observing(other, observed))
        .onMessage(other.value(), "corr-y", TRACEPARENT);
    assertNull(MDC.get(LogFields.CORRELATION_ID));
  }

  @Test
  void onMessagePropagatesAFailedDispatchAsAnException() {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(new NotificationNotFoundException(id)));
    final NotificationDispatchListener listener = new NotificationDispatchListener(useCase);

    assertThrows(
        NotificationNotFoundException.class, () -> listener.onMessage(id.value(), null, null));
  }

  @Test
  void constructorRejectsNullUseCase() {
    assertThrows(NullPointerException.class, () -> new NotificationDispatchListener(null));
  }
}
