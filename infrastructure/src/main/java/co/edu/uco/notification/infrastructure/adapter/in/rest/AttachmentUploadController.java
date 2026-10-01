package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.in.CompleteAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.in.GetAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.in.IssueAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.in.IssuedUpload;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/attachment-uploads")
public class AttachmentUploadController {

  private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentUploadController.class);

  private final IssueAttachmentUploadUseCase issueUseCase;
  private final CompleteAttachmentUploadUseCase completeUseCase;
  private final GetAttachmentUploadUseCase getUseCase;

  public AttachmentUploadController(
      final IssueAttachmentUploadUseCase issueUseCase,
      final CompleteAttachmentUploadUseCase completeUseCase,
      final GetAttachmentUploadUseCase getUseCase) {
    this.issueUseCase = Preconditions.requireNonNull(issueUseCase, "issueUseCase must not be null");
    this.completeUseCase =
        Preconditions.requireNonNull(completeUseCase, "completeUseCase must not be null");
    this.getUseCase = Preconditions.requireNonNull(getUseCase, "getUseCase must not be null");
  }

  @PostMapping
  public Mono<ResponseEntity<AttachmentUploadResponse>> issue(
      final AuthenticatedPrincipal principal,
      @RequestBody final IssueAttachmentUploadRequest request) {
    return Mono.defer(
            () ->
                issueUseCase.issue(
                    principal.tenantId(),
                    request.fileName(),
                    request.contentType(),
                    request.sizeBytes()))
        .doOnNext(issued -> log("issued", issued.upload()))
        .map(
            issued ->
                ResponseEntity.created(URI.create("/attachment-uploads/" + uploadIdOf(issued)))
                    .body(AttachmentUploadResponse.issued(issued)));
  }

  @PostMapping("/{uploadId}:complete")
  public Mono<ResponseEntity<AttachmentUploadResponse>> complete(
      final AuthenticatedPrincipal principal, @PathVariable("uploadId") final String uploadId) {
    return completeUseCase
        .complete(principal.tenantId(), UploadId.of(uploadId))
        .doOnNext(upload -> log("completed", upload))
        .map(
            upload ->
                ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(AttachmentUploadResponse.of(upload)));
  }

  @GetMapping("/{uploadId}")
  public Mono<AttachmentUploadResponse> get(
      final AuthenticatedPrincipal principal, @PathVariable("uploadId") final String uploadId) {
    return getUseCase
        .get(principal.tenantId(), UploadId.of(uploadId))
        .map(AttachmentUploadResponse::of);
  }

  private static String uploadIdOf(final IssuedUpload issued) {
    return AttachmentUpload.keySegment(issued.upload().uploadId().value());
  }

  private static void log(final String action, final AttachmentUpload upload) {
    try (LogContext ignored =
        LogContext.open(null, LogSanitizer.safe(upload.tenantId().value()), null)) {
      LOGGER.info(
          LogFields.fields(
              "action",
              action,
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
