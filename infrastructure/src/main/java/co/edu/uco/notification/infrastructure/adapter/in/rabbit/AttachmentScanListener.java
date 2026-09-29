package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.infrastructure.adapter.out.rabbit.AttachmentScanRequest;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AttachmentScanListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentScanListener.class);

  private final ScanAttachmentUploadUseCase scanAttachmentUploadUseCase;
  private final ObjectMapper objectMapper;

  public AttachmentScanListener(
      final ScanAttachmentUploadUseCase scanAttachmentUploadUseCase,
      final ObjectMapper objectMapper) {
    this.scanAttachmentUploadUseCase =
        Preconditions.requireNonNull(
            scanAttachmentUploadUseCase, "scanAttachmentUploadUseCase must not be null");
    this.objectMapper = Preconditions.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  @RabbitListener(
      queues = "${notification.rabbit.attachment-scan.queue}",
      containerFactory = "attachmentScanListenerContainerFactory")
  public void onMessage(final String payload) throws JsonProcessingException {
    final AttachmentScanRequest request =
        objectMapper.readValue(payload, AttachmentScanRequest.class);
    scanAttachmentUploadUseCase
        .scan(TenantId.of(request.tenantId()), UploadId.of(request.uploadId()))
        .doOnNext(AttachmentScanListener::logVerdict)
        .block();
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
