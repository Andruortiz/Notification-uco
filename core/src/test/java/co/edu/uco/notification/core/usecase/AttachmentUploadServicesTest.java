package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.ScanState;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.port.in.IssuedUpload;
import co.edu.uco.notification.core.port.out.AttachmentScanRequestPort;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.PresignedUpload;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AttachmentUploadServicesTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final TenantId OTHER_TENANT = TenantId.of("tenant-2");
  private static final UploadId UPLOAD_ID = UploadId.of("upload-1");
  private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final long SIZE = 2_000_000L;
  private static final byte[] CONTENT = new byte[(int) SIZE];
  private static final Duration EXPIRATION = Duration.ofMinutes(15);

  private AttachmentUploadRepository repository;
  private AttachmentStoragePort storage;
  private AttachmentScanRequestPort scanRequests;
  private AttachmentInspector inspector;

  @BeforeEach
  void setUp() {
    repository = mock(AttachmentUploadRepository.class);
    storage = mock(AttachmentStoragePort.class);
    scanRequests = mock(AttachmentScanRequestPort.class);
    inspector = mock(AttachmentInspector.class);
    when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(repository.transition(any(), any())).thenReturn(Mono.just(true));
    when(storage.delete(anyString())).thenReturn(Mono.empty());
    when(storage.copyIfMatch(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    when(scanRequests.requestScan(any(), any())).thenReturn(Mono.empty());
  }

  private static AttachmentUpload pending() {
    return AttachmentUpload.issue(
        UPLOAD_ID, TENANT, "contract.pdf", "application/pdf", SIZE, NOW, NOW.plus(EXPIRATION));
  }

  @Nested
  class Issue {

    private IssueAttachmentUploadService service() {
      return new IssueAttachmentUploadService(repository, storage, EXPIRATION, CLOCK);
    }

    @Test
    void createsAPendingUploadScopedByTenantAndReturnsTheSignedUrl() {
      when(storage.presignUpload(anyString(), eq(EXPIRATION)))
          .thenReturn(
              Mono.just(new PresignedUpload("http://minio/b/key?sig=1", NOW.plus(EXPIRATION))));

      final IssuedUpload issued =
          service().issue(TENANT, "contract.pdf", "application/pdf", SIZE).block();

      final ArgumentCaptor<AttachmentUpload> inserted =
          ArgumentCaptor.forClass(AttachmentUpload.class);
      verify(repository).insert(inserted.capture());
      assertEquals(ScanState.PENDING_SCAN, inserted.getValue().state());
      assertEquals(TENANT, inserted.getValue().tenantId());
      assertEquals(NOW.plus(EXPIRATION), inserted.getValue().expiresAt());
      verify(storage).presignUpload(inserted.getValue().uploadKey(), EXPIRATION);
      assertEquals("http://minio/b/key?sig=1", issued.uploadUrl());
      assertEquals(NOW.plus(EXPIRATION), issued.expiresAt());
      assertEquals(inserted.getValue(), issued.upload());
      assertFalse(issued.toString().contains("sig=1"), issued.toString());
    }

    @Test
    void rejectsAnInvalidRequestWithoutTouchingTheStorage() {
      StepVerifier.create(
              Mono.defer(() -> service().issue(TENANT, "small.pdf", "application/pdf", 10L)))
          .expectError(InvalidAttachmentException.class)
          .verify();

      verify(storage, never()).presignUpload(anyString(), any());
      verify(repository, never()).insert(any());
    }

    @Test
    void aStorageFailureCreatesNoUpload() {
      when(storage.presignUpload(anyString(), any()))
          .thenReturn(Mono.error(new AttachmentInspectionUnavailableException("down")));

      StepVerifier.create(service().issue(TENANT, "contract.pdf", "application/pdf", SIZE))
          .expectError(AttachmentInspectionUnavailableException.class)
          .verify();

      verify(repository, never()).insert(any());
    }
  }

  @Nested
  class Complete {

    private CompleteAttachmentUploadService service() {
      return new CompleteAttachmentUploadService(repository, storage, scanRequests, CLOCK);
    }

    @Test
    void anUnknownOrForeignUploadIsNotFound() {
      when(repository.findByTenantAndId(OTHER_TENANT, UPLOAD_ID)).thenReturn(Mono.empty());

      StepVerifier.create(service().complete(OTHER_TENANT, UPLOAD_ID))
          .expectError(AttachmentUploadNotFoundException.class)
          .verify();

      verify(storage, never()).stat(anyString());
    }

    @Test
    void aMissingFileIsNotUploadedYet() {
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(pending()));
      when(storage.stat(anyString())).thenReturn(Mono.empty());

      StepVerifier.create(service().complete(TENANT, UPLOAD_ID))
          .expectError(AttachmentNotUploadedException.class)
          .verify();

      verify(scanRequests, never()).requestScan(any(), any());
    }

    @Test
    void aFileOfAnotherSizeIsDiscardedAndTheUploadStaysPending() {
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(pending()));
      when(storage.stat(anyString())).thenReturn(Mono.just(new StoredObjectInfo(SIZE + 1, "e1")));

      StepVerifier.create(service().complete(TENANT, UPLOAD_ID))
          .expectErrorSatisfies(
              error -> {
                assertEquals(InvalidAttachmentException.class, error.getClass());
                assertEquals(
                    "upload: the uploaded file size does not match sizeBytes (contract.pdf)",
                    error.getMessage());
              })
          .verify();

      verify(storage).delete(pending().uploadKey());
      verify(repository, never()).transition(any(), any());
      verify(scanRequests, never()).requestScan(any(), any());
    }

    @Test
    void aCorrectFileIsMarkedCompletedAndItsScanIsRequested() {
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(pending()));
      when(storage.stat(anyString())).thenReturn(Mono.just(new StoredObjectInfo(SIZE, "e1")));

      final AttachmentUpload completed = service().complete(TENANT, UPLOAD_ID).block();

      assertEquals(NOW, completed.completedAt());
      assertEquals(ScanState.PENDING_SCAN, completed.state());
      verify(repository).transition(pending(), pending().markCompleted(NOW));
      verify(scanRequests).requestScan(TENANT, UPLOAD_ID);
    }

    @Test
    void anAlreadyResolvedUploadIsReturnedWithoutScanningAgain() {
      final AttachmentUpload clean =
          pending().markClean(Sha256Digest.of("x".getBytes(StandardCharsets.UTF_8)), NOW);
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(clean));

      assertEquals(clean, service().complete(TENANT, UPLOAD_ID).block());

      verify(storage, never()).stat(anyString());
      verify(scanRequests, never()).requestScan(any(), any());
    }
  }

  @Nested
  class Get {

    @Test
    void returnsTheUploadOfTheTenantOrNotFound() {
      final GetAttachmentUploadService service = new GetAttachmentUploadService(repository);
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(pending()));
      when(repository.findByTenantAndId(OTHER_TENANT, UPLOAD_ID)).thenReturn(Mono.empty());

      assertEquals(pending(), service.get(TENANT, UPLOAD_ID).block());
      StepVerifier.create(service.get(OTHER_TENANT, UPLOAD_ID))
          .expectError(AttachmentUploadNotFoundException.class)
          .verify();
    }
  }

  @Nested
  class Scan {

    private final Sha256Digest sha = Sha256Digest.of(CONTENT);

    private ScanAttachmentUploadService service() {
      return new ScanAttachmentUploadService(repository, storage, inspector, CLOCK);
    }

    private void givenStoredObject() {
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(pending()));
      when(storage.stat(pending().uploadKey()))
          .thenReturn(Mono.just(new StoredObjectInfo(SIZE, "etag-1")));
      when(storage.read(pending().uploadKey(), "etag-1")).thenReturn(Mono.just(CONTENT));
    }

    @Test
    void aCleanFileIsCopiedToItsImmutableKeyThenMarkedCleanThenTheUploadKeyIsDeleted() {
      givenStoredObject();
      when(inspector.inspect(TENANT, "contract.pdf", "application/pdf", CONTENT))
          .thenReturn(Mono.just(AttachmentInspection.clean(sha)));

      final AttachmentUpload result = service().scan(TENANT, UPLOAD_ID).block();

      assertEquals(ScanState.CLEAN, result.state());
      final InOrder order = inOrder(storage, repository);
      order.verify(storage).copyIfMatch(pending().uploadKey(), result.cleanKey(), "etag-1");
      order.verify(repository).transition(pending(), pending().markClean(sha, NOW));
      order.verify(storage).delete(pending().uploadKey());
    }

    @Test
    void anInfectedFileIsDeletedAndMarkedInfectedWithReasonAndSignature() {
      givenStoredObject();
      when(inspector.inspect(any(), any(), any(), any()))
          .thenReturn(
              Mono.just(
                  AttachmentInspection.rejected(
                      sha, AttachmentRejectionReason.MALWARE, "Eicar-Test-Signature", null)));

      final AttachmentUpload result = service().scan(TENANT, UPLOAD_ID).block();

      assertEquals(ScanState.INFECTED, result.state());
      assertEquals("Eicar-Test-Signature", result.signature());
      final InOrder order = inOrder(storage, repository);
      order.verify(storage).delete(pending().uploadKey());
      order
          .verify(repository)
          .transition(
              pending(),
              pending()
                  .markInfected(
                      sha, AttachmentRejectionReason.MALWARE, "Eicar-Test-Signature", NOW));
      verify(storage, never()).copyIfMatch(anyString(), anyString(), anyString());
    }

    @Test
    void aFileWhoseTypeDoesNotMatchIsMarkedInfectedWithThatReason() {
      givenStoredObject();
      when(inspector.inspect(any(), any(), any(), any()))
          .thenReturn(
              Mono.just(
                  AttachmentInspection.rejected(
                      sha, AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, null, "image/png")));

      final AttachmentUpload result = service().scan(TENANT, UPLOAD_ID).block();

      assertEquals(AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, result.rejectionReason());
      assertNull(result.signature());
    }

    @Test
    void anAlreadyResolvedOrUnknownUploadIsIgnored() {
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID))
          .thenReturn(Mono.just(pending().markClean(sha, NOW)));
      when(repository.findByTenantAndId(OTHER_TENANT, UPLOAD_ID)).thenReturn(Mono.empty());

      StepVerifier.create(service().scan(TENANT, UPLOAD_ID)).verifyComplete();
      StepVerifier.create(service().scan(OTHER_TENANT, UPLOAD_ID)).verifyComplete();

      verify(storage, never()).stat(anyString());
    }

    @Test
    void aMissingObjectLeavesTheUploadPending() {
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(pending()));
      when(storage.stat(anyString())).thenReturn(Mono.empty());

      StepVerifier.create(service().scan(TENANT, UPLOAD_ID)).verifyComplete();

      verify(repository, never()).transition(any(), any());
    }

    @Test
    void aReplacedObjectOfAnotherSizeIsDiscardedAndTheUploadStaysPending() {
      when(repository.findByTenantAndId(TENANT, UPLOAD_ID)).thenReturn(Mono.just(pending()));
      when(storage.stat(anyString())).thenReturn(Mono.just(new StoredObjectInfo(10, "etag-2")));
      when(storage.read(anyString(), eq("etag-2"))).thenReturn(Mono.just(new byte[10]));

      StepVerifier.create(service().scan(TENANT, UPLOAD_ID)).verifyComplete();

      verify(storage).delete(pending().uploadKey());
      verify(inspector, never()).inspect(any(), any(), any(), any());
      verify(repository, never()).transition(any(), any());
    }

    @Test
    void aFileChangedAfterTheScanIsNotFixedAndTheErrorAsksForARetry() {
      givenStoredObject();
      when(inspector.inspect(any(), any(), any(), any()))
          .thenReturn(Mono.just(AttachmentInspection.clean(sha)));
      when(storage.copyIfMatch(anyString(), anyString(), anyString()))
          .thenReturn(Mono.error(new AttachmentObjectChangedException()));

      StepVerifier.create(service().scan(TENANT, UPLOAD_ID))
          .expectError(AttachmentObjectChangedException.class)
          .verify();

      verify(repository, never()).transition(any(), any());
      verify(storage, never()).delete(anyString());
    }

    @Test
    void anUnavailableAntivirusAsksForARetryWithoutChangingState() {
      givenStoredObject();
      when(inspector.inspect(any(), any(), any(), any()))
          .thenReturn(Mono.error(new AttachmentInspectionUnavailableException("down")));

      StepVerifier.create(service().scan(TENANT, UPLOAD_ID))
          .expectError(AttachmentInspectionUnavailableException.class)
          .verify();

      verify(repository, never()).transition(any(), any());
    }

    @Test
    void aLostTransitionRaceIsNotReportedAsTheWinner() {
      givenStoredObject();
      when(inspector.inspect(any(), any(), any(), any()))
          .thenReturn(Mono.just(AttachmentInspection.clean(sha)));
      when(repository.transition(any(), any())).thenReturn(Mono.just(false));

      StepVerifier.create(service().scan(TENANT, UPLOAD_ID)).verifyComplete();

      verify(storage, never()).delete(anyString());
    }
  }

  @Test
  void constructorsRejectNulls() {
    assertThrows(
        NullPointerException.class,
        () -> new IssueAttachmentUploadService(null, storage, EXPIRATION, CLOCK));
    assertThrows(
        NullPointerException.class,
        () -> new CompleteAttachmentUploadService(repository, null, scanRequests, CLOCK));
    assertThrows(NullPointerException.class, () -> new GetAttachmentUploadService(null));
    assertThrows(
        NullPointerException.class,
        () -> new ScanAttachmentUploadService(repository, storage, null, CLOCK));
  }
}
