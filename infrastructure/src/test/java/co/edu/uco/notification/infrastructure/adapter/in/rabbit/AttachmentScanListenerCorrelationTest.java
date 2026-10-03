package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.infrastructure.config.AttachmentScanTopologyProperties;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.TraceParent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import reactor.core.publisher.Mono;

class AttachmentScanListenerCorrelationTest {

  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
  private static final TenantId TENANT = TenantId.of("tenant-1");

  private final ScanAttachmentUploadUseCase scanUseCase = mock(ScanAttachmentUploadUseCase.class);
  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final MessageRecoverer recoverer = mock(MessageRecoverer.class);
  private final Channel channel = mock(Channel.class);

  private final AttachmentScanListener listener =
      new AttachmentScanListener(
          scanUseCase,
          new ObjectMapper(),
          rabbitTemplate,
          recoverer,
          new AttachmentScanTopologyProperties("ex", "rk", "q", "dlx", "dlrk", "dlq"),
          new AttachmentProperties(
              new AttachmentProperties.Scan(Duration.ofSeconds(5), Duration.ofSeconds(5), 1, 2),
              null,
              null,
              null));

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

  private static Message messageWith(final UploadId uploadId, final MessageProperties props) {
    final String json = "{\"tenantId\":\"tenant-1\",\"uploadId\":\"" + uploadId.value() + "\"}";
    return new Message(json.getBytes(StandardCharsets.UTF_8), props);
  }

  @Test
  void theHeaderIdAndTraceparentReachTheContextAndTheMdcAndTheMessageIsAcked() throws Exception {
    final UploadId uploadId = UploadId.newId();
    final AtomicReference<String> contextId = new AtomicReference<>();
    final AtomicReference<String> contextTrace = new AtomicReference<>();
    final AtomicReference<String> mdcId = new AtomicReference<>();
    when(scanUseCase.scan(eq(TENANT), eq(uploadId)))
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  contextId.set(context.getOrDefault(CorrelationId.CONTEXT_KEY, null));
                  contextTrace.set(context.getOrDefault(TraceParent.CONTEXT_KEY, null));
                  mdcId.set(MDC.get(LogFields.CORRELATION_ID));
                  return Mono.empty();
                }));
    final MessageProperties props = new MessageProperties();
    props.setHeader(CorrelationId.AMQP_HEADER, "corr-scan");
    props.setHeader(TraceParent.HEADER, TRACEPARENT);

    listener.onMessage(messageWith(uploadId, props), channel, 7L);

    assertEquals("corr-scan", contextId.get());
    assertEquals(TRACEPARENT, contextTrace.get());
    assertEquals("corr-scan", mdcId.get());
    verify(channel).basicAck(7L, false);
    assertNull(MDC.get(LogFields.CORRELATION_ID));
  }

  @Test
  void aMissingHeaderFallsBackToALegacyIdAndNoTraceparent() throws Exception {
    final UploadId uploadId = UploadId.newId();
    final AtomicReference<String> contextId = new AtomicReference<>();
    final AtomicReference<String> contextTrace = new AtomicReference<>();
    when(scanUseCase.scan(eq(TENANT), eq(uploadId)))
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  contextId.set(context.getOrDefault(CorrelationId.CONTEXT_KEY, null));
                  contextTrace.set(context.getOrDefault(TraceParent.CONTEXT_KEY, null));
                  return Mono.empty();
                }));

    listener.onMessage(messageWith(uploadId, new MessageProperties()), channel, 8L);

    assertTrue(contextId.get().startsWith(NotificationDispatchListener.LEGACY_PREFIX));
    assertNull(contextTrace.get());
    verify(channel).basicAck(8L, false);
  }

  @Test
  void aFailureStillAcksAndRepublishesWithTheOriginalHeadersAndCleansTheMdc() throws Exception {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(eq(TENANT), eq(uploadId)))
        .thenReturn(Mono.error(new IllegalStateException("scanner down")));
    final MessageProperties props = new MessageProperties();
    props.setHeader(CorrelationId.AMQP_HEADER, "corr-retry");

    listener.onMessage(messageWith(uploadId, props), channel, 9L);

    final org.mockito.ArgumentCaptor<Message> republished =
        org.mockito.ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq("ex"), eq("rk"), republished.capture());
    assertEquals(
        "corr-retry",
        republished.getValue().getMessageProperties().getHeaders().get(CorrelationId.AMQP_HEADER));
    verify(channel).basicAck(9L, false);
    assertNull(MDC.get(LogFields.CORRELATION_ID));
    verify(recoverer, org.mockito.Mockito.never()).recover(any(), any());
  }

  @Test
  void aFailedRetryPublicationRejectsTheMessageWithoutRequeueAndNeverAcksIt() throws Exception {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(eq(TENANT), eq(uploadId)))
        .thenReturn(Mono.error(new IllegalStateException("scanner down")));
    doThrow(new AmqpException("broker down"))
        .when(rabbitTemplate)
        .send(eq("ex"), eq("rk"), any(Message.class));

    listener.onMessage(messageWith(uploadId, new MessageProperties()), channel, 10L);

    verify(channel).basicNack(10L, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void aFailedDeadLetterPublicationRejectsTheMessageWithoutRequeueAndNeverAcksIt()
      throws Exception {
    final UploadId uploadId = UploadId.newId();
    final MessageProperties props = new MessageProperties();
    props.setHeader("x-scan-attempt", 1);
    when(scanUseCase.scan(eq(TENANT), eq(uploadId)))
        .thenReturn(Mono.error(new IllegalStateException("scanner down")));
    doThrow(new AmqpException("dlq down")).when(recoverer).recover(any(), any());

    listener.onMessage(messageWith(uploadId, props), channel, 11L);

    verify(channel).basicNack(11L, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void anUnreadableBodyIsDeadLetteredWithoutCallingTheScanAndWithoutCountingAnAttempt()
      throws Exception {
    final Message poison =
        new Message("{broken".getBytes(StandardCharsets.UTF_8), new MessageProperties());

    listener.onMessage(poison, channel, 12L);

    verify(recoverer).recover(eq(poison), any());
    verify(scanUseCase, never()).scan(any(), any());
    verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    verify(channel).basicAck(12L, false);
  }

  @Test
  void everyRepublicationWaitsForTheBrokerConfirmationBeforeAcking() throws Exception {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(eq(TENANT), eq(uploadId)))
        .thenReturn(Mono.error(new IllegalStateException("scanner down")));
    final RabbitOperations operations = mock(RabbitOperations.class);
    doAnswer(
            invocation ->
                invocation
                    .<RabbitOperations.OperationsCallback<?>>getArgument(0)
                    .doInRabbit(operations))
        .when(rabbitTemplate)
        .invoke(any());
    doThrow(new AmqpException("not confirmed")).when(operations).waitForConfirmsOrDie(anyLong());

    listener.onMessage(messageWith(uploadId, new MessageProperties()), channel, 13L);

    verify(operations).waitForConfirmsOrDie(anyLong());
    verify(channel).basicNack(13L, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }
}
