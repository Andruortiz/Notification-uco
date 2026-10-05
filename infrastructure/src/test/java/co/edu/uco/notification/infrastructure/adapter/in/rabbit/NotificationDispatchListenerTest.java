package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.exception.DispatchResultNotPersistedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.TraceParent;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import reactor.core.publisher.Mono;

class NotificationDispatchListenerTest {

  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
  private static final long TAG = 5L;

  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final MessageRecoverer recoverer = mock(MessageRecoverer.class);
  private final Channel channel = mock(Channel.class);
  private final RabbitTopologyProperties properties =
      new RabbitTopologyProperties(
          new RabbitTopologyProperties.Dispatch("dex", "drk", "dq"),
          new RabbitTopologyProperties.Dlq("lex", "lrk", "lq"),
          "events");

  @BeforeEach
  void runPublicationsInsideInvoke() {
    when(rabbitTemplate.invoke(any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<RabbitOperations.OperationsCallback<?>>getArgument(0)
                    .doInRabbit(rabbitTemplate));
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private NotificationDispatchListener listenerFor(
      final DispatchNotificationUseCase useCase, final int maxAttempts) {
    return new NotificationDispatchListener(
        useCase, rabbitTemplate, recoverer, properties, maxAttempts);
  }

  private static Message message(
      final String body, final String correlationHeader, final String traceparentHeader) {
    final MessageProperties properties = new MessageProperties();
    if (correlationHeader != null) {
      properties.setHeader(CorrelationId.AMQP_HEADER, correlationHeader);
    }
    if (traceparentHeader != null) {
      properties.setHeader(TraceParent.HEADER, traceparentHeader);
    }
    return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
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
  void onMessageDispatchesTheNotificationWithTheGivenIdAndAcknowledgesIt() throws IOException {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    when(useCase.dispatch(eq(id))).thenReturn(Mono.empty());

    listenerFor(useCase, 3).onMessage(message(id.value(), "corr-1", null), channel, TAG);

    verify(useCase).dispatch(id);
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void theMessageIsAcknowledgedOnlyAfterTheDispatchHasCompleted() throws IOException {
    final NotificationId id = NotificationId.newId();
    final AtomicBoolean persisted = new AtomicBoolean(false);
    final AtomicBoolean persistedAtAckTime = new AtomicBoolean(false);
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    when(useCase.dispatch(eq(id))).thenReturn(Mono.<Void>fromRunnable(() -> persisted.set(true)));
    doAnswer(
            invocation -> {
              persistedAtAckTime.set(persisted.get());
              return null;
            })
        .when(channel)
        .basicAck(TAG, false);

    listenerFor(useCase, 3).onMessage(message(id.value(), null, null), channel, TAG);

    assertTrue(persistedAtAckTime.get());
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void aFailedDispatchIsNeverAcknowledgedBeforeBeingRepublishedAndTheListenerDoesNotThrow()
      throws IOException {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(new NotificationNotFoundException(id)));
    final Message original = message(id.value(), "corr-x", null);

    listenerFor(useCase, 3).onMessage(original, channel, TAG);

    final ArgumentCaptor<Message> republished = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq("dex"), eq("drk"), republished.capture());
    assertEquals(
        1,
        republished
            .getValue()
            .getMessageProperties()
            .getHeaders()
            .get(NotificationDispatchListener.ATTEMPT_HEADER));
    assertEquals(
        "corr-x",
        republished.getValue().getMessageProperties().getHeaders().get(CorrelationId.AMQP_HEADER));
    verify(channel).basicAck(TAG, false);
    verify(recoverer, never()).recover(any(), any());
  }

  @Test
  void whenTheAttemptsAreExhaustedTheMessageGoesToTheDeadLetterQueueWithTheCause()
      throws IOException {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    final NotificationNotFoundException failure = new NotificationNotFoundException(id);
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(failure));
    final Message original = message(id.value(), null, null);
    original.getMessageProperties().setHeader(NotificationDispatchListener.ATTEMPT_HEADER, 2);

    listenerFor(useCase, 3).onMessage(original, channel, TAG);

    verify(recoverer).recover(original, failure);
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void aResultThatCouldNotBePersistedGoesStraightToTheDeadLetterQueueWithoutRepublishing()
      throws IOException {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    final DispatchResultNotPersistedException failure =
        new DispatchResultNotPersistedException(id, new IllegalStateException("mongo down"));
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(failure));
    final Message original = message(id.value(), null, null);

    listenerFor(useCase, 3).onMessage(original, channel, TAG);

    verify(recoverer).recover(original, failure);
    verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void aBlankBodyGoesStraightToTheDeadLetterQueueWithoutCallingTheUseCase() throws IOException {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final Message original = message("  ", null, null);

    listenerFor(useCase, 3).onMessage(original, channel, TAG);

    verify(useCase, never()).dispatch(any());
    verify(recoverer).recover(eq(original), any(RuntimeException.class));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void aPublicationThatIsNotConfirmedRejectsTheMessageInsteadOfAcknowledgingIt()
      throws IOException {
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    final NotificationId id = NotificationId.newId();
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(new NotificationNotFoundException(id)));
    org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpException("publisher nack"))
        .when(rabbitTemplate)
        .waitForConfirmsOrDie(anyLong());

    listenerFor(useCase, 3).onMessage(message(id.value(), null, null), channel, TAG);

    verify(channel).basicNack(TAG, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void aValidHeaderBecomesTheCorrelationIdInTheContextAndTheMdcDuringTheDispatch()
      throws IOException {
    final NotificationId id = NotificationId.newId();
    final AtomicReference<Observed> observed = new AtomicReference<>();

    listenerFor(observing(id, observed), 3)
        .onMessage(message(id.value(), "corr-header", TRACEPARENT), channel, TAG);

    final Observed seen = observed.get();
    assertEquals("corr-header", seen.contextCorrelationId());
    assertEquals(id.value(), seen.contextNotificationId());
    assertEquals(TRACEPARENT, seen.contextTraceparent());
    assertEquals("corr-header", seen.mdcCorrelationId());
    assertEquals(id.value(), seen.mdcNotificationId());
    assertEquals(TRACEPARENT, seen.mdcTraceparent());
  }

  @Test
  void aMissingHeaderFallsBackToALegacyPrefixedId() throws IOException {
    final NotificationId id = NotificationId.newId();
    final AtomicReference<Observed> observed = new AtomicReference<>();

    listenerFor(observing(id, observed), 3)
        .onMessage(message(id.value(), null, null), channel, TAG);

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
  void anInvalidHeaderIsNeverPropagatedAndFallsBackToALegacyId() throws IOException {
    final NotificationId id = NotificationId.newId();
    final AtomicReference<Observed> observed = new AtomicReference<>();

    listenerFor(observing(id, observed), 3)
        .onMessage(message(id.value(), "bad id\nforged", "garbage"), channel, TAG);

    assertTrue(observed.get().contextCorrelationId().startsWith("legacy-"));
    assertNull(observed.get().contextTraceparent());
    assertNull(observed.get().mdcTraceparent());
  }

  @Test
  void theMdcIsRestoredAfterTheMessageWhetherItSucceedsOrFails() throws IOException {
    final NotificationId id = NotificationId.newId();
    final DispatchNotificationUseCase useCase = mock(DispatchNotificationUseCase.class);
    when(useCase.dispatch(eq(id))).thenReturn(Mono.error(new NotificationNotFoundException(id)));

    listenerFor(useCase, 3).onMessage(message(id.value(), "corr-x", null), channel, TAG);

    assertNull(MDC.get(LogFields.CORRELATION_ID));
    assertNull(MDC.get(LogFields.NOTIFICATION_ID));
    assertNull(MDC.get(LogFields.TRACE_PARENT));

    final AtomicReference<Observed> observed = new AtomicReference<>();
    final NotificationId other = NotificationId.newId();
    listenerFor(observing(other, observed), 3)
        .onMessage(message(other.value(), "corr-y", TRACEPARENT), channel, TAG);
    assertNull(MDC.get(LogFields.CORRELATION_ID));
  }

  @Test
  void constructorRejectsNullUseCase() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> new NotificationDispatchListener(null, rabbitTemplate, recoverer, properties, 3));
  }
}
