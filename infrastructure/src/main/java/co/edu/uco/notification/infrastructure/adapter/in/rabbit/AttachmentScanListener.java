package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.infrastructure.adapter.out.rabbit.AttachmentScanRequest;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.infrastructure.config.AttachmentScanTopologyProperties;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.infrastructure.config.ReactorObservations;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
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
  private final ManualAckSettler settler;
  private final ObservationRegistry observationRegistry;

  public AttachmentScanListener(
      final ScanAttachmentUploadUseCase scanAttachmentUploadUseCase,
      final ObjectMapper objectMapper,
      final RabbitTemplate rabbitTemplate,
      @Qualifier("attachmentScanDlqRecoverer") final MessageRecoverer attachmentScanDlqRecoverer,
      final AttachmentScanTopologyProperties topology,
      final AttachmentProperties attachmentProperties,
      final ObservationRegistry observationRegistry) {
    this.scanAttachmentUploadUseCase =
        Preconditions.requireNonNull(
            scanAttachmentUploadUseCase, "scanAttachmentUploadUseCase must not be null");
    this.objectMapper = Preconditions.requireNonNull(objectMapper, "objectMapper must not be null");
    Preconditions.requireNonNull(topology, "topology must not be null");
    this.settler =
        new ManualAckSettler(
            rabbitTemplate,
            attachmentScanDlqRecoverer,
            topology.exchange(),
            topology.routingKey(),
            ATTEMPT_HEADER,
            attachmentProperties.scan().maxAttempts(),
            "Attachment scan");
    this.observationRegistry =
        Preconditions.requireNonNull(observationRegistry, "observationRegistry must not be null");
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
    try (LogContext ignored = LogContext.open(correlationId, null, null)) {
      process(message, channel, deliveryTag, correlationId);
    }
  }

  private void process(
      final Message message,
      final Channel channel,
      final long deliveryTag,
      final CorrelationId correlationId)
      throws IOException {
    final AttachmentScanRequest request;
    try {
      request = objectMapper.readValue(message.getBody(), AttachmentScanRequest.class);
      TenantId.of(request.tenantId());
      UploadId.of(request.uploadId());
    } catch (final RuntimeException | IOException cause) {
      LOGGER.warn(
          LogFields.failure(ErrorCode.ATTACHMENT_SCAN_MESSAGE_UNREADABLE),
          "Attachment scan message is unreadable, sending it to the dead-letter queue",
          cause);
      settler.deadLetter(message, channel, deliveryTag, cause);
      return;
    }
    Exception failure = null;
    try {
      scanAttachmentUploadUseCase
          .scan(TenantId.of(request.tenantId()), UploadId.of(request.uploadId()))
          .doOnNext(AttachmentScanListener::logVerdict)
          .contextWrite(
              ReactorObservations.with(
                  Context.of(CorrelationId.CONTEXT_KEY, correlationId.value()),
                  observationRegistry.getCurrentObservation()))
          .block();
    } catch (final RuntimeException cause) {
      failure = cause;
    }
    if (failure == null) {
      settler.acknowledge(channel, deliveryTag);
    } else {
      handleFailure(message, channel, deliveryTag, request, failure);
    }
  }

  private void handleFailure(
      final Message message,
      final Channel channel,
      final long deliveryTag,
      final AttachmentScanRequest request,
      final Exception cause)
      throws IOException {
    if (settler.isLastAttempt(message) && isObjectChanged(cause)) {
      failExhaustedUpload(request);
    }
    settler.handleFailure(message, channel, deliveryTag, cause);
  }

  private void failExhaustedUpload(final AttachmentScanRequest request) {
    try {
      scanAttachmentUploadUseCase
          .failExhausted(TenantId.of(request.tenantId()), UploadId.of(request.uploadId()))
          .block();
    } catch (final RuntimeException failure) {
      LOGGER.error(
          LogFields.failure(ErrorCode.ATTACHMENT_UPLOAD_NOT_FAILED),
          "Attachment upload could not be failed after exhausting the scan attempts",
          failure);
    }
  }

  private static boolean isObjectChanged(final Throwable cause) {
    Throwable current = cause;
    while (current != null) {
      if (current instanceof AttachmentObjectChangedException) {
        return true;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return false;
  }

  private static void logVerdict(final AttachmentUpload upload) {
    try (LogContext ignored = LogContext.open(null, upload.tenantId().value(), null)) {
      LOGGER.info(
          LogFields.fields(
              "uploadId",
              upload.uploadId().value(),
              "fileName",
              LogSanitizer.safe(upload.fileName()),
              "contentType",
              upload.contentType(),
              "sizeBytes",
              upload.sizeBytes(),
              "sha256",
              upload.sha256() == null ? null : upload.sha256().hex(),
              "state",
              upload.state(),
              "reason",
              upload.rejectionReason(),
              "signature",
              LogSanitizer.safe(upload.signature())),
          "Attachment upload scanned");
    }
  }
}
