package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.policy.AttachmentPolicy;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ScanState;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AttachmentNotReadyException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class AttachmentResolver {

  private static final String NOT_ISSUED =
      "url is not an upload issued by this service for this tenant";

  private final AttachmentInspector inspector;
  private final AttachmentUploadRepository uploadRepository;
  private final AttachmentStoragePort storagePort;

  public AttachmentResolver(
      final AttachmentInspector inspector,
      final AttachmentUploadRepository uploadRepository,
      final AttachmentStoragePort storagePort) {
    this.inspector = Preconditions.requireNonNull(inspector, "inspector must not be null");
    this.uploadRepository =
        Preconditions.requireNonNull(uploadRepository, "uploadRepository must not be null");
    this.storagePort = Preconditions.requireNonNull(storagePort, "storagePort must not be null");
  }

  public Mono<List<Attachment>> resolve(
      final TenantId tenantId, final List<AttachmentSubmission> submissions) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(submissions, "submissions must not be null");
    return Flux.range(0, submissions.size())
        .concatMap(position -> resolve(tenantId, position, submissions.get(position)))
        .collectList();
  }

  private Mono<Attachment> resolve(
      final TenantId tenantId, final int position, final AttachmentSubmission submission) {
    return submission.isEmbedded()
        ? resolveEmbedded(tenantId, position, submission)
        : resolveReference(tenantId, position, submission);
  }

  private Mono<Attachment> resolveEmbedded(
      final TenantId tenantId, final int position, final AttachmentSubmission submission) {
    return Mono.fromCallable(() -> AttachmentPolicy.decode(position, submission))
        .flatMap(
            bytes ->
                inspector
                    .inspect(tenantId, submission.fileName(), submission.contentType(), bytes)
                    .map(
                        inspection ->
                            toAttachment(tenantId, position, submission, bytes, inspection)));
  }

  private static Attachment toAttachment(
      final TenantId tenantId,
      final int position,
      final AttachmentSubmission submission,
      final byte[] bytes,
      final AttachmentInspection inspection) {
    if (!inspection.isClean()) {
      throw rejection(position, submission, inspection.rejectionReason());
    }
    return new Attachment(
        tenantId,
        submission.fileName(),
        submission.contentType(),
        bytes.length,
        inspection.sha256(),
        new AttachmentSource.EmbeddedContent(bytes));
  }

  private Mono<Attachment> resolveReference(
      final TenantId tenantId, final int position, final AttachmentSubmission submission) {
    return Mono.justOrEmpty(
            storagePort
                .parseUploadUrl(submission.url())
                .flatMap(key -> AttachmentUpload.uploadIdFromKey(tenantId, key)))
        .flatMap(uploadId -> uploadRepository.findByTenantAndId(tenantId, uploadId))
        .filter(upload -> upload.tenantId().equals(tenantId))
        .switchIfEmpty(
            Mono.error(
                () -> new InvalidAttachmentException(position, NOT_ISSUED, submission.fileName())))
        .map(upload -> toAttachment(tenantId, position, submission, upload));
  }

  private static Attachment toAttachment(
      final TenantId tenantId,
      final int position,
      final AttachmentSubmission submission,
      final AttachmentUpload upload) {
    if (!matchesUpload(submission, upload)) {
      throw new InvalidAttachmentException(
          position,
          "fileName, contentType and sizeBytes must match the upload",
          submission.fileName());
    }
    if (upload.state() == ScanState.PENDING_SCAN) {
      throw new AttachmentNotReadyException(position);
    }
    if (upload.state() == ScanState.INFECTED) {
      throw new InvalidAttachmentException(
          position, "the upload was rejected by the scan", submission.fileName());
    }
    if (upload.state() == ScanState.FAILED) {
      throw new InvalidAttachmentException(
          position, "the upload failed and cannot be used", submission.fileName());
    }
    return new Attachment(
        tenantId,
        upload.fileName(),
        upload.contentType(),
        upload.sizeBytes(),
        upload.sha256(),
        new AttachmentSource.StoredObject(upload.uploadId(), upload.cleanKey()));
  }

  private static boolean matchesUpload(
      final AttachmentSubmission submission, final AttachmentUpload upload) {
    return Objects.equals(submission.fileName(), upload.fileName())
        && Objects.equals(submission.contentType(), upload.contentType())
        && submission.sizeBytes() != null
        && submission.sizeBytes() == upload.sizeBytes();
  }

  private static InvalidAttachmentException rejection(
      final int position,
      final AttachmentSubmission submission,
      final AttachmentRejectionReason reason) {
    final String rule =
        reason == AttachmentRejectionReason.MALWARE
            ? "the file contains malicious software"
            : "the content is not " + submission.contentType();
    return new InvalidAttachmentException(position, rule, submission.fileName());
  }
}
