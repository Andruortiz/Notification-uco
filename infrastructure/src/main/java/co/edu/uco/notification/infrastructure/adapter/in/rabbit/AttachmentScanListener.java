package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.infrastructure.adapter.out.rabbit.AttachmentScanRequest;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.infrastructure.config.AttachmentScanTopologyProperties;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import co.edu.uco.notification.utils.TraceParent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;

@Component
public class AttachmentScanListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentScanListener.class);
  private static final String ATTEMPT_HEADER = "x-scan-attempt";

  private final ScanAttachmentUploadUseCase scanAttachmentUploadUseCase;
  private final ObjectMapper objectMapper;
  private final RabbitTemplate rabbitTemplate;
  private final MessageRecoverer deadLetterRecoverer;
  private final AttachmentScanTopologyProperties topology;
  private final int maxAttempts;

  public AttachmentScanListener(
      final ScanAttachmentUploadUseCase scanAttachmentUploadUseCase,
      final ObjectMapper objectMapper,
      final RabbitTemplate rabbitTemplate,
      @Qualifier("attachmentScanDlqRecoverer") final MessageRecoverer attachmentScanDlqRecoverer,
      final AttachmentScanTopologyProperties topology,
      final AttachmentProperties attachmentProperties) {
    this.scanAttachmentUploadUseCase =
        Preconditions.requireNonNull(
            scanAttachmentUploadUseCase, "scanAttachmentUploadUseCase must not be null");
    this.objectMapper = Preconditions.requireNonNull(objectMapper, "objectMapper must not be null");
    this.rabbitTemplate =
        Preconditions.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
    this.deadLetterRecoverer =
        Preconditions.requireNonNull(
            attachmentScanDlqRecoverer, "attachmentScanDlqRecoverer must not be null");
    this.topology = Preconditions.requireNonNull(topology, "topology must not be null");
    this.maxAttempts = Math.max(1, attachmentProperties.scan().maxAttempts());
  }

  @RabbitListener(
      queues = "${notification.rabbit.attachment-scan.queue}",
      containerFactory = "attachmentScanListenerContainerFactory")
  public void onMessage(
      final Message message,
      final Channel channel,
      @Header(AmqpHeaders.DELIVERY_TAG) final long deliveryTag)
      throws IOException {
    final CorrelationId fromHeader = CorrelationContext.fromHeaders(message.getMessageProperties());
    final CorrelationId correlationId =
        fromHeader != null
            ? fromHeader
            : CorrelationId.of(
                NotificationDispatchListener.LEGACY_PREFIX + CorrelationId.newId().value());
    final TraceParent traceParent =
        CorrelationContext.traceFromHeaders(message.getMessageProperties());
    try (LogContext ignored =
        LogContext.open(correlationId, null, null).withTraceParent(traceParent)) {
      process(message, channel, deliveryTag, correlationId, traceParent);
    }
  }

  private void process(
      final Message message,
      final Channel channel,
      final long deliveryTag,
      final CorrelationId correlationId,
      final TraceParent traceParent)
      throws IOException {
    Exception failure = null;
    try {
      final AttachmentScanRequest request =
          objectMapper.readValue(message.getBody(), AttachmentScanRequest.class);
      scanAttachmentUploadUseCase
          .scan(TenantId.of(request.tenantId()), UploadId.of(request.uploadId()))
          .doOnNext(AttachmentScanListener::logVerdict)
          .contextWrite(
              traceParent == null
                  ? Context.of(CorrelationId.CONTEXT_KEY, correlationId.value())
                  : Context.of(
                      CorrelationId.CONTEXT_KEY,
                      correlationId.value(),
                      TraceParent.CONTEXT_KEY,
                      traceParent.value()))
          .block();
    } catch (final RuntimeException | IOException cause) {
      failure = cause;
    }
    if (failure == null) {
      channel.basicAck(deliveryTag, false);
    } else {
      handleFailure(message, channel, deliveryTag, failure);
    }
  }

  private void handleFailure(
      final Message message, final Channel channel, final long deliveryTag, final Exception cause)
      throws IOException {
    final int attempt = attemptCount(message) + 1;
    if (attempt >= maxAttempts) {
      LOGGER.warn(
          "Attachment scan exhausted {} attempts, sending the message to the dead-letter queue",
          attempt,
          cause);
      deadLetterRecoverer.recover(message, cause);
    } else {
      LOGGER.warn(
          "Attachment scan attempt {} of {} failed, requeueing", attempt, maxAttempts, cause);
      rabbitTemplate.send(
          topology.exchange(), topology.routingKey(), withAttempt(message, attempt));
    }
    channel.basicAck(deliveryTag, false);
  }

  private static int attemptCount(final Message message) {
    final Object value = message.getMessageProperties().getHeaders().get(ATTEMPT_HEADER);
    return value instanceof Integer count ? count : 0;
  }

  private static Message withAttempt(final Message message, final int attempt) {
    return MessageBuilder.fromMessage(message).setHeader(ATTEMPT_HEADER, attempt).build();
  }

  private static void logVerdict(final AttachmentUpload upload) {
    LOGGER.info(
        "Attachment upload scanned tenantId={} uploadId={} fileName={} contentType={} sizeBytes={}"
            + " sha256={} state={} reason={} signature={}",
        upload.tenantId().value(),
        upload.uploadId().value(),
        sanitize(upload.fileName()),
        upload.contentType(),
        upload.sizeBytes(),
        upload.sha256() == null ? null : upload.sha256().hex(),
        upload.state(),
        upload.rejectionReason(),
        sanitize(upload.signature()));
  }

  private static String sanitize(final String value) {
    if (value == null) {
      return null;
    }
    final StringBuilder safe = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(code -> safe.appendCodePoint(Character.isISOControl(code) ? '_' : code));
    return safe.toString();
  }
}
