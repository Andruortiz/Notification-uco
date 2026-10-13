package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExpireAbandonedUploadsServiceTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration DEADLINE = Duration.ofHours(1);

  private AttachmentUploadRepository repository;
  private AttachmentStoragePort storage;
  private ExpireAbandonedUploadsService service;

  @BeforeEach
  void setUp() {
    repository = mock(AttachmentUploadRepository.class);
    storage = mock(AttachmentStoragePort.class);
    when(repository.transition(any(), any())).thenReturn(Mono.just(true));
    when(storage.delete(anyString())).thenReturn(Mono.empty());
    service = new ExpireAbandonedUploadsService(repository, storage, DEADLINE, 50, CLOCK);
  }

  private static AttachmentUpload abandoned(final String id) {
    return AttachmentUpload.issue(
        UploadId.of(id),
        TENANT,
        "contract.pdf",
        "application/pdf",
        2_000_000L,
        NOW.minus(Duration.ofHours(2)),
        NOW.minus(Duration.ofHours(2)).plusSeconds(900));
  }

  @Test
  void anUploadNeverCompletedFailsWithExpiredAfterItsStateIsPersisted() {
    final AttachmentUpload upload = abandoned("u-1");
    when(repository.findStalePending(NOW, NOW.minus(DEADLINE), 50)).thenReturn(Flux.just(upload));

    StepVerifier.create(service.expire()).expectNext(1L).verifyComplete();

    final InOrder order = inOrder(repository, storage);
    order
        .verify(repository)
        .transition(upload, upload.markFailed(AttachmentRejectionReason.EXPIRED, NOW));
    order.verify(storage).delete(upload.uploadKey());
  }

  @Test
  void anUploadWhoseScanNeverFinishedFailsWithScanExhausted() {
    final AttachmentUpload upload = abandoned("u-2").markCompleted(NOW.minus(Duration.ofHours(2)));
    when(repository.findStalePending(NOW, NOW.minus(DEADLINE), 50)).thenReturn(Flux.just(upload));

    StepVerifier.create(service.expire()).expectNext(1L).verifyComplete();

    verify(repository)
        .transition(upload, upload.markFailed(AttachmentRejectionReason.SCAN_EXHAUSTED, NOW));
  }

  @Test
  void anUploadAnotherReplicaAlreadyResolvedIsNeitherCountedNorDeleted() {
    final AttachmentUpload upload = abandoned("u-3");
    when(repository.findStalePending(NOW, NOW.minus(DEADLINE), 50)).thenReturn(Flux.just(upload));
    when(repository.transition(any(), any())).thenReturn(Mono.just(false));

    StepVerifier.create(service.expire()).expectNext(0L).verifyComplete();

    verify(storage, never()).delete(anyString());
  }

  @Test
  void aStorageFailureAfterThePersistedTransitionDoesNotStopTheBatch() {
    final AttachmentUpload first = abandoned("u-4");
    final AttachmentUpload second = abandoned("u-5");
    when(repository.findStalePending(NOW, NOW.minus(DEADLINE), 50))
        .thenReturn(Flux.just(first, second));
    when(storage.delete(first.uploadKey()))
        .thenReturn(Mono.error(new AttachmentInspectionUnavailableException("down")));

    StepVerifier.create(service.expire()).expectNext(2L).verifyComplete();

    verify(storage).delete(second.uploadKey());
  }

  @Test
  void nothingStaleDoesNothing() {
    when(repository.findStalePending(any(), any(), anyInt())).thenReturn(Flux.empty());

    StepVerifier.create(service.expire()).expectNext(0L).verifyComplete();

    verify(repository, never()).transition(any(), any());
  }

  @Test
  void rejectsInvalidConstruction() {
    assertThrows(
        NullPointerException.class,
        () -> new ExpireAbandonedUploadsService(null, storage, DEADLINE, 1, CLOCK));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExpireAbandonedUploadsService(repository, storage, DEADLINE, 0, CLOCK));
  }
}
