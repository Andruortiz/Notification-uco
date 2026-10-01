package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.ScanVerdict;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemOutcome;
import co.edu.uco.notification.core.port.in.BatchItemResult;
import co.edu.uco.notification.core.port.in.BatchNotificationItem;
import co.edu.uco.notification.core.port.in.SendNotificationBatchCommand;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.ContentTypeDetectorPort;
import co.edu.uco.notification.core.port.out.MalwareScannerPort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.ScanVerdictCachePort;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.core.repository.NotificationBatchRepository;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class SendNotificationBatchServiceTest {

  private static final TenantId TENANT_ID = TenantId.of("tenant-1");

  private final SendNotificationUseCase sendNotificationUseCase =
      Mockito.mock(SendNotificationUseCase.class);
  private final NotificationBatchRepository notificationBatchRepository =
      Mockito.mock(NotificationBatchRepository.class);

  private SendNotificationBatchService service;

  @BeforeEach
  void setUp() {
    when(notificationBatchRepository.save(any(), any())).thenReturn(Mono.empty());
    service =
        new SendNotificationBatchService(sendNotificationUseCase, notificationBatchRepository);
  }

  private static BatchNotificationItem item(final String externalId) {
    return new BatchNotificationItem(
        ExternalId.of(externalId),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  private static SendNotificationCommand toCommand(final BatchNotificationItem item) {
    return new SendNotificationCommand(
        TENANT_ID,
        item.externalId(),
        item.channelType(),
        item.recipientId(),
        item.recipient(),
        item.content(),
        item.priority());
  }

  @Test
  void sendBatchAggregatesAcceptedDuplicateAndRejectedOutcomes() {
    final BatchNotificationItem accepted = item("order-1");
    final BatchNotificationItem duplicate = item("order-2");
    final BatchNotificationItem rejected = item("order-3");

    final NotificationId acceptedId = NotificationId.newId();
    final NotificationId duplicateId = NotificationId.newId();

    when(sendNotificationUseCase.send(toCommand(accepted)))
        .thenReturn(
            Mono.just(new SendNotificationResult(acceptedId, NotificationStatus.PENDING, false)));
    when(sendNotificationUseCase.send(toCommand(duplicate)))
        .thenReturn(
            Mono.just(new SendNotificationResult(duplicateId, NotificationStatus.PENDING, true)));
    when(sendNotificationUseCase.send(toCommand(rejected)))
        .thenReturn(Mono.error(new ChannelNotAvailableException(rejected.channelType())));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(
            TENANT_ID, BatchId.of("batch-1"), List.of(accepted, duplicate, rejected));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertEquals(BatchId.of("batch-1"), result.batchId());
    assertEquals(3, result.results().size());

    assertEquals(BatchItemOutcome.ACCEPTED, result.results().get(0).outcome());
    assertEquals(acceptedId, result.results().get(0).notificationId());

    assertEquals(BatchItemOutcome.DUPLICATE, result.results().get(1).outcome());
    assertEquals(duplicateId, result.results().get(1).notificationId());

    assertEquals(BatchItemOutcome.REJECTED, result.results().get(2).outcome());
    assertEquals("Channel not available: EMAIL", result.results().get(2).rejectionReason());
  }

  @Test
  void sendBatchRejectsItemsWithInvalidContent() {
    final BatchNotificationItem rejected = item("order-1");
    when(sendNotificationUseCase.send(toCommand(rejected)))
        .thenReturn(
            Mono.error(new InvalidContentException(rejected.channelType(), "subject is required")));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(TENANT_ID, BatchId.of("batch-1"), List.of(rejected));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertEquals(BatchItemOutcome.REJECTED, result.results().get(0).outcome());
  }

  @Test
  void sendBatchGeneratesBatchIdWhenNoneGiven() {
    final BatchNotificationItem accepted = item("order-1");
    when(sendNotificationUseCase.send(toCommand(accepted)))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(TENANT_ID, null, List.of(accepted));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertNotNull(result.batchId());
  }

  @Test
  void sendBatchRejectsNullCommand() {
    assertThrows(NullPointerException.class, () -> service.sendBatch(null));
  }

  @Test
  void constructorRejectsNullSendNotificationUseCase() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationBatchService(null, notificationBatchRepository));
  }

  @Test
  void constructorRejectsNullNotificationBatchRepository() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationBatchService(sendNotificationUseCase, null));
  }

  @Test
  void sendBatchPersistsTheBatchRecordWithTheTenantAndTheResult() {
    final BatchNotificationItem accepted = item("order-1");
    when(sendNotificationUseCase.send(toCommand(accepted)))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(TENANT_ID, BatchId.of("batch-1"), List.of(accepted));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    final ArgumentCaptor<BatchAcceptedResult> resultCaptor =
        ArgumentCaptor.forClass(BatchAcceptedResult.class);
    final ArgumentCaptor<TenantId> tenantCaptor = ArgumentCaptor.forClass(TenantId.class);
    verify(notificationBatchRepository).save(resultCaptor.capture(), tenantCaptor.capture());
    assertEquals(result, resultCaptor.getValue());
    assertEquals(TENANT_ID, tenantCaptor.getValue());
  }

  @Test
  void sendBatchStillReturnsTheResultWhenPersistingTheBatchRecordFails() {
    when(notificationBatchRepository.save(any(), any()))
        .thenReturn(Mono.error(new IllegalStateException("mongo is unreachable")));
    final BatchNotificationItem accepted = item("order-1");
    final NotificationId acceptedId = NotificationId.newId();
    when(sendNotificationUseCase.send(toCommand(accepted)))
        .thenReturn(
            Mono.just(new SendNotificationResult(acceptedId, NotificationStatus.PENDING, false)));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(TENANT_ID, BatchId.of("batch-1"), List.of(accepted));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertEquals(BatchId.of("batch-1"), result.batchId());
    assertEquals(BatchItemOutcome.ACCEPTED, result.results().get(0).outcome());
    assertEquals(acceptedId, result.results().get(0).notificationId());
  }

  private static final String ATTACHMENTS_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"attachments\":{\"type\":\"array\"}}}";
  private static final byte[] PDF = "%PDF-1.4 batch".getBytes(StandardCharsets.UTF_8);
  private static final String SIGNATURE = "X-Amz-Signature=secret-7788";
  private static final UploadId PENDING_UPLOAD = UploadId.of("pending-upload");
  private static final long LARGE = 2_000_000L;

  @Test
  void sendBatchRejectsOnlyTheItemsWithAnInvalidOrNotReadyAttachment() {
    final RealPipeline pipeline = new RealPipeline();
    pipeline.givenScannerVerdict(Mono.just(ScanVerdict.clean("1")));

    final BatchAcceptedResult result =
        pipeline
            .service()
            .sendBatch(
                new SendNotificationBatchCommand(
                    TENANT_ID,
                    BatchId.of("batch-attachments"),
                    List.of(
                        itemWith("order-invalid", embeddedPdf(PDF.length + 1L)),
                        itemWith("order-pending", pendingReference()),
                        itemWith("order-valid", embeddedPdf(PDF.length)))))
            .block();

    assertNotNull(result);
    final BatchItemResult invalid = result.results().get(0);
    final BatchItemResult pending = result.results().get(1);
    assertEquals(BatchItemOutcome.REJECTED, invalid.outcome());
    assertTrue(
        invalid.rejectionReason().startsWith("attachments[0]: sizeBytes does not match"),
        invalid.rejectionReason());
    assertEquals(BatchItemOutcome.REJECTED, pending.outcome());
    assertTrue(
        pending.rejectionReason().contains("still being scanned"), pending.rejectionReason());
    assertFalse(pending.rejectionReason().contains("secret-7788"), pending.rejectionReason());
    assertEquals(BatchItemOutcome.ACCEPTED, result.results().get(2).outcome());
  }

  @Test
  void anUnavailableInspectionBecomesAFailedItemInsteadOfFailingTheBatch() {
    final RealPipeline pipeline = new RealPipeline();
    pipeline.givenScannerVerdict(Mono.error(new IllegalStateException("clamav down")));

    final BatchAcceptedResult result =
        pipeline
            .service()
            .sendBatch(
                new SendNotificationBatchCommand(
                    TENANT_ID,
                    BatchId.of("batch-scanner-down"),
                    List.of(itemWith("order-1", embeddedPdf(PDF.length)))))
            .block();

    assertNotNull(result);
    assertEquals(1, result.results().size());
    assertEquals(BatchItemOutcome.FAILED, result.results().get(0).outcome());
  }

  @Test
  void anUnavailableInspectionFailsOnlyItsOwnItemWhileOthersAreAccepted() {
    final RealPipeline pipeline = new RealPipeline();
    final byte[] failingContent = "%PDF-1.4 failing".getBytes(StandardCharsets.UTF_8);
    pipeline.givenScannerVerdict(
        content ->
            java.util.Arrays.equals(content, failingContent)
                ? Mono.error(new IllegalStateException("clamav down"))
                : Mono.just(ScanVerdict.clean("1")));

    final BatchAcceptedResult result =
        pipeline
            .service()
            .sendBatch(
                new SendNotificationBatchCommand(
                    TENANT_ID,
                    BatchId.of("batch-mixed"),
                    List.of(
                        itemWith("order-1", embeddedPdf(PDF.length)),
                        itemWith(
                            "order-2",
                            AttachmentSubmission.embedded(
                                "broken.pdf",
                                "application/pdf",
                                (long) failingContent.length,
                                Base64.getEncoder().encodeToString(failingContent))),
                        itemWith("order-3", embeddedPdf(PDF.length)))))
            .block();

    assertNotNull(result);
    assertEquals(3, result.results().size());
    assertEquals("order-1", result.results().get(0).externalId().value());
    assertEquals(BatchItemOutcome.ACCEPTED, result.results().get(0).outcome());
    assertEquals("order-2", result.results().get(1).externalId().value());
    assertEquals(BatchItemOutcome.FAILED, result.results().get(1).outcome());
    assertEquals("order-3", result.results().get(2).externalId().value());
    assertEquals(BatchItemOutcome.ACCEPTED, result.results().get(2).outcome());
  }

  private static AttachmentSubmission embeddedPdf(final long declaredSize) {
    return AttachmentSubmission.embedded(
        "invoice.pdf", "application/pdf", declaredSize, Base64.getEncoder().encodeToString(PDF));
  }

  private static AttachmentSubmission pendingReference() {
    return AttachmentSubmission.reference(
        "contract.pdf",
        "application/pdf",
        LARGE,
        "http://minio.test/bucket/"
            + AttachmentUpload.uploadKeyFor(TENANT_ID, PENDING_UPLOAD)
            + "?"
            + SIGNATURE);
  }

  private static BatchNotificationItem itemWith(
      final String externalId, final AttachmentSubmission attachment) {
    return new BatchNotificationItem(
        ExternalId.of(externalId),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Subject", "Body"),
        Priority.NORMAL,
        List.of(attachment));
  }

  private static final class RealPipeline {

    private final ChannelCatalogPort channelCatalogPort = Mockito.mock(ChannelCatalogPort.class);
    private final NotificationRepository notificationRepository =
        Mockito.mock(NotificationRepository.class);
    private final NotificationEventPublisherPort eventPublisherPort =
        Mockito.mock(NotificationEventPublisherPort.class);
    private final ContentTypeDetectorPort detector = Mockito.mock(ContentTypeDetectorPort.class);
    private final MalwareScannerPort scanner = Mockito.mock(MalwareScannerPort.class);
    private final ScanVerdictCachePort cache = Mockito.mock(ScanVerdictCachePort.class);
    private final AttachmentUploadRepository uploads =
        Mockito.mock(AttachmentUploadRepository.class);
    private final AttachmentStoragePort storage = Mockito.mock(AttachmentStoragePort.class);
    private final NotificationBatchRepository notificationBatchRepository =
        Mockito.mock(NotificationBatchRepository.class);

    RealPipeline() {
      when(notificationBatchRepository.save(any(), any())).thenReturn(Mono.empty());
      when(channelCatalogPort.findActiveRoute(ChannelType.of("EMAIL"), TENANT_ID))
          .thenReturn(
              Mono.just(
                  new ChannelRoute(
                      ChannelType.of("EMAIL"),
                      List.of(ProviderId.of("simulated")),
                      ATTACHMENTS_SCHEMA)));
      when(notificationRepository.findByTenantAndExternalId(any(), any())).thenReturn(Mono.empty());
      when(notificationRepository.save(any(Notification.class)))
          .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
      when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
      when(eventPublisherPort.enqueueForDispatch(any(Notification.class))).thenReturn(Mono.empty());
      when(detector.detect(any(), any())).thenReturn(Mono.just("application/pdf"));
      when(cache.find(any(), any())).thenReturn(Mono.empty());
      when(cache.save(any(), any(), any())).thenReturn(Mono.empty());
      when(storage.parseUploadUrl(any()))
          .thenAnswer(
              invocation ->
                  Optional.of(
                      ((String) invocation.getArgument(0))
                          .substring("http://minio.test/bucket/".length())
                          .split("\\?")[0]));
      when(uploads.findByTenantAndId(TENANT_ID, PENDING_UPLOAD))
          .thenReturn(
              Mono.just(
                  AttachmentUpload.issue(
                      PENDING_UPLOAD,
                      TENANT_ID,
                      "contract.pdf",
                      "application/pdf",
                      LARGE,
                      Instant.now(),
                      Instant.now().plusSeconds(900))));
    }

    void givenScannerVerdict(final Mono<ScanVerdict> verdict) {
      when(scanner.scan(any())).thenReturn(verdict);
    }

    void givenScannerVerdict(
        final java.util.function.Function<byte[], Mono<ScanVerdict>> verdictFn) {
      when(scanner.scan(any()))
          .thenAnswer(invocation -> verdictFn.apply(invocation.getArgument(0)));
    }

    SendNotificationBatchService service() {
      return new SendNotificationBatchService(
          new SendNotificationService(
              channelCatalogPort,
              notificationRepository,
              eventPublisherPort,
              new AttachmentResolver(
                  new AttachmentInspector(detector, scanner, cache), uploads, storage)),
          notificationBatchRepository);
    }
  }

  @Test
  void sendBatchPassesTheBatchCorrelationIdToEveryItem() {
    final CorrelationId correlationId = CorrelationId.of("batch-corr");
    final ArgumentCaptor<SendNotificationCommand> captured =
        ArgumentCaptor.forClass(SendNotificationCommand.class);
    when(sendNotificationUseCase.send(captured.capture()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(
            TENANT_ID,
            BatchId.of("batch-corr-1"),
            List.of(item("order-1"), item("order-2")),
            correlationId);

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertEquals(2, captured.getAllValues().size());
    captured.getAllValues().forEach(item -> assertEquals(correlationId, item.correlationId()));
  }
}
