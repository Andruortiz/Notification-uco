package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.port.in.ExpireAbandonedUploadsUseCase;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import reactor.core.publisher.Mono;

public final class ExpireAbandonedUploadsService implements ExpireAbandonedUploadsUseCase {

  private final AttachmentUploadRepository repository;
  private final AttachmentStoragePort storage;
  private final Duration scanDeadline;
  private final int batchSize;
  private final Clock clock;

  public ExpireAbandonedUploadsService(
      final AttachmentUploadRepository repository,
      final AttachmentStoragePort storage,
      final Duration scanDeadline,
      final int batchSize,
      final Clock clock) {
    this.repository = Preconditions.requireNonNull(repository, "repository must not be null");
    this.storage = Preconditions.requireNonNull(storage, "storage must not be null");
    this.scanDeadline = Preconditions.requireNonNull(scanDeadline, "scanDeadline must not be null");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    this.batchSize = batchSize;
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Mono<Long> expire() {
    final Instant now = clock.instant();
    return repository
        .findStalePending(now, now.minus(scanDeadline), batchSize)
        .concatMap(upload -> fail(upload, now))
        .count();
  }

  private Mono<AttachmentUpload> fail(final AttachmentUpload upload, final Instant now) {
    final AttachmentRejectionReason reason =
        upload.completedAt() == null
            ? AttachmentRejectionReason.EXPIRED
            : AttachmentRejectionReason.SCAN_EXHAUSTED;
    final AttachmentUpload failed = upload.markFailed(reason, now);
    return repository
        .transition(upload, failed)
        .filter(Boolean::booleanValue)
        .flatMap(
            won ->
                StorageCleanup.bestEffort(storage.delete(upload.uploadKey())).thenReturn(failed));
  }
}
