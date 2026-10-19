package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.CompleteAttachmentUploadUseCase;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/attachment-upload-completions")
public class AttachmentUploadCompletionController {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AttachmentUploadCompletionController.class);

  private final CompleteAttachmentUploadUseCase completeUseCase;

  public AttachmentUploadCompletionController(
      final CompleteAttachmentUploadUseCase completeUseCase) {
    this.completeUseCase =
        Preconditions.requireNonNull(completeUseCase, "completeUseCase must not be null");
  }

  @PostMapping
  public Mono<ResponseEntity<AttachmentUploadResponse>> complete(
      final AuthenticatedPrincipal principal,
      @RequestBody final CompleteAttachmentUploadRequest request) {
    return Mono.defer(
            () -> completeUseCase.complete(principal.tenantId(), UploadId.of(request.uploadId())))
        .doOnNext(AttachmentUploadCompletionController::log)
        .map(
            upload ->
                ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(AttachmentUploadResponse.of(upload)));
  }

  private static void log(final AttachmentUpload upload) {
    try (LogContext ignored =
        LogContext.open(null, LogSanitizer.safe(upload.tenantId().value()), null)) {
      LOGGER.info(
          LogFields.fields(
              "action",
              "completed",
              "uploadId",
              upload.uploadId().value(),
              "fileName",
              LogSanitizer.safe(upload.fileName()),
              "contentType",
              upload.contentType(),
              "sizeBytes",
              upload.sizeBytes(),
              "state",
              upload.state()),
          "Attachment upload");
    }
  }
}
